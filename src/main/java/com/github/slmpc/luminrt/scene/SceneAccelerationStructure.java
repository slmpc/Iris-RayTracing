package com.github.slmpc.luminrt.scene;

import com.github.slmpc.luminrt.rhi.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** 静态几何按键替换，动态几何仅在索引与布局不变时 refit；所有实例使用 anchor 相对坐标。 */
public final class SceneAccelerationStructure implements AutoCloseable {
    /** 实例平移的参考原点；相机在重定位半径内移动不会重写实例或 TLAS。 */
    public record Anchor(double x, double y, double z) {}

    public record Statistics(int meshes, long blasBuilds, long blasUpdates, long tlasBuilds, long tlasUpdates,
                             long anchorRebases, long instanceBytes, long uvBytes, long materialBytes,
                             long headerBytes, long uploadBatches, long uploadCount) {}

    /** anchor 落在该整数网格上，避免相机轻微移动就重定位。 */
    private static final double ANCHOR_GRID = 64.0;
    /** 相机离 anchor 超过该距离时重定位并重写全部实例。 */
    private static final double REBASE_DISTANCE = 256.0;

    private final AccelerationStructureBackend backend;
    private final Map<Object, MeshResource> meshes = new LinkedHashMap<>();
    private final List<MeshResource> retired = new ArrayList<>();
    private AccelerationStructure tlas;
    private AccelerationStructureBuffer instances;
    private AccelerationStructureBuffer uvData;
    private AccelerationStructureBuffer materialData;
    private AccelerationStructureBuffer header;
    private Anchor anchor;
    private long anchorGeneration;
    private boolean uvDirty = true;
    private boolean materialDirty = true;
    private int instanceCount = -1;
    private boolean dirty = true;
    private boolean structural = true;
    private long blasBuilds, blasUpdates, tlasBuilds, tlasUpdates, anchorRebases;
    private long instanceBytes, uvBytes, materialBytes, headerBytes, uploadBatches, uploadCount;

    public SceneAccelerationStructure(AccelerationStructureBackend backend) {
        this.backend = Objects.requireNonNull(backend);
    }

    public Statistics statistics() {
        return new Statistics(meshes.size(), blasBuilds, blasUpdates, tlasBuilds, tlasUpdates,
                anchorRebases, instanceBytes, uvBytes, materialBytes, headerBytes, uploadBatches, uploadCount);
    }

    /** 已发布的 TLAS；尚未构建时返回 null，调用方不得在此触发构建。 */
    public AccelerationStructure currentTlas() { return tlas; }

    public boolean hasTlas() { return tlas != null; }

    /** 当前实例参考原点；首次构建前为 null。 */
    public Anchor anchor() { return anchor; }

    public long anchorGeneration() { return anchorGeneration; }

    public void put(Object key, List<TriangleMesh> geometry, double x, double y, double z, boolean dynamic) {
        Objects.requireNonNull(key);
        geometry = List.copyOf(geometry);
        if (geometry.isEmpty()) { remove(key); return; }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("Non-finite origin");
        var previous = meshes.get(key);
        dirty = true;
        if (dynamic && previous != null && previous.dynamic && previous.sameTopology(geometry)) {
            // 仅位置或实例原点改变时复用 UV 表，避免每帧重传整个地形的 UV。
            for (int i = 0; i < geometry.size(); i++) {
                uvDirty |= !previous.geometry.get(i).sameUVs(geometry.get(i));
                materialDirty |= !previous.geometry.get(i).sameMaterials(geometry.get(i));
            }
            previous.upload(geometry, false);
            backend.update(previous.blas);
            previous.x = x; previous.y = y; previous.z = z;
            blasUpdates++;
        } else {
            var next = new MeshResource(geometry, x, y, z, dynamic);
            uvDirty = materialDirty = true;
            meshes.put(key, next);
            if (previous != null) retired.add(previous);
            blasBuilds++;
            structural = true;
        }
    }

    public void remove(Object key) {
        var old = meshes.remove(key);
        if (old != null) { retired.add(old); dirty = structural = uvDirty = materialDirty = true; }
    }

    public AccelerationStructure build(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("Non-finite camera");
        // 相机移动本身不再触发任何重写；只有离开 anchor 半径才重定位并重写实例。
        boolean initialized = anchor != null;
        boolean rebase = !initialized
                || Math.abs(x - anchor.x()) > REBASE_DISTANCE
                || Math.abs(y - anchor.y()) > REBASE_DISTANCE
                || Math.abs(z - anchor.z()) > REBASE_DISTANCE;
        if (rebase) {
            anchor = new Anchor(quantize(x), quantize(y), quantize(z));
            anchorGeneration++;
            if (initialized) anchorRebases++;
            dirty = true;
        }
        if (!dirty) return tlas;
        int count = meshes.size();
        if (count > 0x1000000) throw new IllegalStateException("Instance custom index exceeds 24 bits");
        var data = MemoryUtil.memCalloc(Math.multiplyExact(Math.max(1, count), 64)).order(ByteOrder.nativeOrder());
        AccelerationStructure nextTlas = tlas;
        AccelerationStructureBuffer nextInstances = instances;
        boolean replace = tlas == null || instanceCount != count;
        AccelerationStructureBuffer nextUV = uvData;
        AccelerationStructureBuffer nextMaterial = materialData;
        boolean writeUV = uvDirty;
        boolean writeMaterial = materialDirty;
        long writtenUV = 0, writtenMaterial = 0;
        var uploads = new ArrayList<AccelerationStructureBackend.Upload>();
        var uploadData = new ArrayList<ByteBuffer>();
        try {
            if (writeUV) {
                var geometry = meshes.values().stream().map(resource -> resource.geometry).toList();
                var packed = MemoryUtil.memAlloc(SceneUVData.byteSize(geometry)).order(ByteOrder.nativeOrder());
                SceneUVData.write(packed, geometry); packed.flip();
                nextUV = backend.createInputBuffer(packed.remaining());
                uploads.add(new AccelerationStructureBackend.Upload(nextUV, 0, packed));
                uploadData.add(packed);
                writtenUV = packed.remaining();
            }
            if (writeMaterial) {
                var geometry = meshes.values().stream().map(resource -> resource.geometry).toList();
                var packed = MemoryUtil.memAlloc(SceneMaterialData.byteSize(geometry)).order(ByteOrder.nativeOrder());
                var dynamicInstances = meshes.values().stream().map(resource -> resource.dynamic).toList();
                SceneMaterialData.write(packed, geometry, dynamicInstances); packed.flip();
                nextMaterial = backend.createInputBuffer(packed.remaining());
                uploads.add(new AccelerationStructureBackend.Upload(nextMaterial, 0, packed));
                uploadData.add(packed);
                writtenMaterial = packed.remaining();
            }
            if (header == null) header = backend.createInputBuffer(SceneHeaderData.SIZE);
            var packedHeader = MemoryUtil.memAlloc(SceneHeaderData.SIZE).order(ByteOrder.nativeOrder());
            SceneHeaderData.write(packedHeader, anchor, anchorGeneration, count); packedHeader.flip();
            uploads.add(new AccelerationStructureBackend.Upload(header, 0, packedHeader));
            uploadData.add(packedHeader);
            int index = 0;
            for (var resource : meshes.values()) {
                int offset = index * 64;
                // 实例平移相对 anchor，相机在半径内移动时记录保持有效。
                data.putFloat(offset, 1).putFloat(offset + 12, (float)(resource.x - anchor.x()));
                data.putFloat(offset + 20, 1).putFloat(offset + 28, (float)(resource.y - anchor.y()));
                data.putFloat(offset + 40, 1).putFloat(offset + 44, (float)(resource.z - anchor.z()));
                data.putInt(offset + 48, index | (0xff << 24));
                // 禁止三角形背面剔除，保留薄片和双面模型的几何。
                data.putInt(offset + 52, 1 << 24);
                data.putLong(offset + 56, resource.blas.deviceAddress());
                index++;
            }
            if (replace) {
                nextInstances = backend.createInputBuffer(data.remaining());
                nextTlas = backend.create(new AccelerationStructureDescription(AccelerationStructure.Type.TOP_LEVEL,
                        List.of(new AccelerationGeometry.Instances(nextInstances, 0, count)), true, false));
            }
            uploads.add(new AccelerationStructureBackend.Upload(nextInstances, 0, data));
            backend.upload(uploads);
            uploadBatches++;
            uploadCount += uploads.size();
            if (!replace && !structural && count != 0) { backend.update(nextTlas); tlasUpdates++; }
            else { backend.build(nextTlas); tlasBuilds++; }
            nextTlas.setReferencedStructures(meshes.values().stream().map(resource -> resource.blas).toList());
            if (writeUV) {
                if (uvData != null) uvData.close();
                uvData = nextUV;
                uvDirty = false;
                uvBytes += writtenUV;
            }
            if (writeMaterial) {
                if (materialData != null) materialData.close();
                materialData = nextMaterial;
                materialDirty = false;
                materialBytes += writtenMaterial;
            }
            if (replace) {
                if (tlas != null) tlas.close();
                if (instances != null) instances.close();
                tlas = nextTlas; instances = nextInstances; instanceCount = count;
            }
            // 新 TLAS 已持有当前 BLAS，旧 TLAS/已录制命令通过底层租约保护旧 BLAS。
            retired.forEach(MeshResource::close);
            retired.clear();
            instanceBytes += (long) count * 64;
            headerBytes += SceneHeaderData.SIZE;
            dirty = structural = false;
            return tlas;
        } catch (RuntimeException | Error failure) {
            if (nextUV != null && nextUV != uvData) nextUV.close();
            if (nextMaterial != null && nextMaterial != materialData) nextMaterial.close();
            if (replace) {
                if (nextTlas != null && nextTlas != tlas) nextTlas.close();
                if (nextInstances != null && nextInstances != instances) nextInstances.close();
            }
            throw failure;
        } finally {
            uploadData.forEach(MemoryUtil::memFree);
            MemoryUtil.memFree(data);
        }
    }

    public AccelerationStructureBuffer uvBuffer() {
        if (uvData == null || uvData.isClosed()) throw new IllegalStateException("Scene must be built before accessing UV data");
        return uvData;
    }

    public AccelerationStructureBuffer materialBuffer() {
        if (materialData == null) throw new IllegalStateException("材质表尚未构建");
        return materialData;
    }

    public AccelerationStructureBuffer headerBuffer() {
        if (header == null) throw new IllegalStateException("场景头尚未构建");
        return header;
    }

    @Override public void close() {
        if (tlas != null) tlas.close();
        if (instances != null) instances.close();
        if (uvData != null) uvData.close();
        if (materialData != null) materialData.close();
        if (header != null) header.close();
        meshes.values().forEach(MeshResource::close);
        retired.forEach(MeshResource::close);
        meshes.clear(); retired.clear();
        tlas = null; instances = uvData = materialData = header = null; instanceCount = -1;
        anchor = null; anchorGeneration = 0;
        dirty = structural = uvDirty = materialDirty = true;
    }

    /** anchor 取整到固定网格，避免相机轻微移动造成频繁重写。 */
    private static double quantize(double value) {
        return Math.floor(value / ANCHOR_GRID) * ANCHOR_GRID;
    }

    private final class MeshResource implements AutoCloseable {
        private final List<AccelerationStructureBuffer> vertices = new ArrayList<>(), indices = new ArrayList<>();
        private final List<TriangleMesh> topology;
        private List<TriangleMesh> geometry;
        private final boolean dynamic;
        private AccelerationStructure blas;
        private double x, y, z;

        private MeshResource(List<TriangleMesh> geometry, double x, double y, double z, boolean dynamic) {
            this.topology = dynamic ? geometry : List.of();
            this.dynamic = dynamic; this.x = x; this.y = y; this.z = z;
            try {
                var descriptions = new ArrayList<AccelerationGeometry>();
                for (var mesh : geometry) {
                    var vertex = backend.createInputBuffer(mesh.vertexCount() * 12L); vertices.add(vertex);
                    var index = backend.createInputBuffer(mesh.triangleCount() * 12L); indices.add(index);
                    descriptions.add(new AccelerationGeometry.Triangles(vertex, 0, mesh.vertexCount(), 12, index, 0,
                            AccelerationGeometry.IndexType.UINT32, mesh.triangleCount(), mesh.opaque()));
                }
                upload(geometry, true);
                blas = backend.create(new AccelerationStructureDescription(AccelerationStructure.Type.BOTTOM_LEVEL, descriptions, dynamic, !dynamic));
                backend.build(blas);
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }

        private boolean sameTopology(List<TriangleMesh> geometry) {
            if (topology.size() != geometry.size()) return false;
            for (int i = 0; i < topology.size(); i++) if (!topology.get(i).sameTopology(geometry.get(i))) return false;
            return true;
        }

        private void upload(List<TriangleMesh> geometry, boolean includeIndices) {
            var uploads = new ArrayList<AccelerationStructureBackend.Upload>(geometry.size() * (includeIndices ? 2 : 1));
            var uploadData = new ArrayList<ByteBuffer>(uploads.size());
            try {
                for (int i = 0; i < geometry.size(); i++) {
                    var mesh = geometry.get(i);
                    var positions = MemoryUtil.memAlloc(Math.multiplyExact(mesh.vertexCount(), 12)).order(ByteOrder.nativeOrder());
                    mesh.writePositions(positions);
                    uploads.add(new AccelerationStructureBackend.Upload(vertices.get(i), 0, positions));
                    uploadData.add(positions);
                    if (includeIndices) {
                        var indexData = MemoryUtil.memAlloc(Math.multiplyExact(mesh.triangleCount(), 12)).order(ByteOrder.nativeOrder());
                        mesh.writeIndices(indexData);
                        uploads.add(new AccelerationStructureBackend.Upload(indices.get(i), 0, indexData));
                        uploadData.add(indexData);
                    }
                }
                backend.upload(uploads);
                this.geometry = geometry;
            } finally {
                uploadData.forEach(MemoryUtil::memFree);
            }
        }

        @Override public void close() {
            if (blas != null) blas.close();
            vertices.forEach(AccelerationStructureBuffer::close);
            indices.forEach(AccelerationStructureBuffer::close);
        }
    }
}
