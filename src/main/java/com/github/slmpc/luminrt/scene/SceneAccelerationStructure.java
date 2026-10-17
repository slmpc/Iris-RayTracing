package com.github.slmpc.luminrt.scene;

import com.github.slmpc.luminrt.rhi.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * 静态几何按键替换，动态几何仅在索引与布局不变时 refit；所有实例使用 anchor 相对坐标。
 *
 * <p>着色数据以 {@link SceneRecordData} 分段存放在 {@link SceneGeometryArena} 中，
 * 目录只包含 header、实例与 geometry 索引；任何变化只重写变化的记录范围。
 */
public final class SceneAccelerationStructure implements AutoCloseable {
    /** 实例平移的参考原点；相机在重定位半径内移动不会重写实例或 TLAS。 */
    public record Anchor(double x, double y, double z) {}

    public record Statistics(int meshes, long blasBuilds, long blasUpdates, long tlasBuilds, long tlasUpdates,
                             long anchorRebases, long instanceBytes, long directoryBytes, long recordBytes,
                             long uploadBatches, long uploadCount, long arenaResident, long arenaCapacity) {}

    /** anchor 落在该整数网格上，避免相机轻微移动就重定位。 */
    private static final double ANCHOR_GRID = 64.0;
    /** 相机离 anchor 超过该距离时重定位并重写全部实例。 */
    private static final double REBASE_DISTANCE = 256.0;
    /** 记录缓冲初始容量；不足时按倍数扩容并重新打包。 */
    private static final int INITIAL_RECORD_BYTES = 4 << 20;

    private final AccelerationStructureBackend backend;
    private final Map<Object, MeshResource> meshes = new LinkedHashMap<>();
    private final List<MeshResource> retired = new ArrayList<>();
    private final SceneGeometryArena records;
    private final List<int[]> pendingRecordFrees = new ArrayList<>();
    private AccelerationStructure tlas;
    private AccelerationStructureBuffer instances;
    private AccelerationStructureBuffer directory;
    private Anchor anchor;
    private long anchorGeneration;
    private int instanceCount = -1;
    private boolean dirty = true;
    private boolean structural = true;
    private long blasBuilds, blasUpdates, tlasBuilds, tlasUpdates, anchorRebases;
    private long instanceBytes, directoryBytes, recordBytes, uploadBatches, uploadCount;

    public SceneAccelerationStructure(AccelerationStructureBackend backend) {
        this.backend = Objects.requireNonNull(backend);
        this.records = new SceneGeometryArena(backend, INITIAL_RECORD_BYTES);
    }

    public Statistics statistics() {
        return new Statistics(meshes.size(), blasBuilds, blasUpdates, tlasBuilds, tlasUpdates,
                anchorRebases, instanceBytes, directoryBytes, recordBytes, uploadBatches, uploadCount,
                records.resident(), records.capacity());
    }

    /** 已发布的 TLAS；尚未构建时返回 null，调用方不得在此触发构建。 */
    public AccelerationStructure currentTlas() { return tlas; }

    public boolean hasTlas() { return tlas != null; }

    /** 当前实例参考原点；首次构建前为 null。 */
    public Anchor anchor() { return anchor; }

    public long anchorGeneration() { return anchorGeneration; }

    public AccelerationStructureBuffer directoryBuffer() {
        if (directory == null || directory.isClosed()) throw new IllegalStateException("场景目录尚未构建");
        return directory;
    }

    public AccelerationStructureBuffer recordsBuffer() {
        return records.buffer();
    }

    public void put(Object key, List<TriangleMesh> geometry, double x, double y, double z, boolean dynamic) {
        Objects.requireNonNull(key);
        geometry = List.copyOf(geometry);
        if (geometry.isEmpty()) { remove(key); return; }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("Non-finite origin");
        var previous = meshes.get(key);
        dirty = true;
        if (dynamic && previous != null && previous.dynamic && previous.sameTopology(geometry)) {
            // 拓扑未变时只 refit；只有内容（位置/UV/颜色/材质）变化才重新打包记录。
            boolean changed = false;
            for (int i = 0; i < geometry.size(); i++)
                changed |= !previous.geometry.get(i).sameContent(geometry.get(i));
            previous.upload(geometry, false);
            if (changed) previous.packRecords();
            backend.update(previous.blas);
            previous.x = x; previous.y = y; previous.z = z;
            blasUpdates++;
        } else {
            var next = new MeshResource(geometry, x, y, z, dynamic);
            meshes.put(key, next);
            if (previous != null) retired.add(previous);
            blasBuilds++;
            structural = true;
        }
    }

    public void remove(Object key) {
        var old = meshes.remove(key);
        if (old != null) { retired.add(old); dirty = structural = true; }
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
        AccelerationStructureBuffer nextDirectory = null;
        int directorySize = 0;
        var uploads = new ArrayList<AccelerationStructureBackend.Upload>();
        var uploadData = new ArrayList<ByteBuffer>();
        try {
            // 目录只含 header、实例与 geometry 索引；记录本体在 arena 中按范围增量维护。
            var instancesView = new ArrayList<SceneGeometryDirectory.Instance>(count);
            var geometries = new ArrayList<SceneGeometryDirectory.Geometry>();
            for (var resource : meshes.values()) {
                int base = geometries.size();
                for (int i = 0; i < resource.geometry.size(); i++) {
                    var mesh = resource.geometry.get(i);
                    int flags = (mesh.quads() ? SceneGeometryDirectory.FLAG_QUADS : 0)
                            | (mesh.opaque() ? SceneGeometryDirectory.FLAG_OPAQUE : 0);
                    geometries.add(new SceneGeometryDirectory.Geometry(
                            resource.recordOffsets.get(i), mesh.recordCount(), flags));
                }
                instancesView.add(new SceneGeometryDirectory.Instance(base, resource.geometry.size()));
            }
            directorySize = SceneGeometryDirectory.byteSize(count, geometries.size());
            var packedDirectory = MemoryUtil.memAlloc(directorySize).order(ByteOrder.nativeOrder());
            SceneGeometryDirectory.write(packedDirectory, (int) anchor.x(), (int) anchor.y(), (int) anchor.z(),
                    anchorGeneration, instancesView, geometries);
            packedDirectory.flip();
            nextDirectory = backend.createInputBuffer(directorySize);
            uploads.add(new AccelerationStructureBackend.Upload(nextDirectory, 0, packedDirectory));
            uploadData.add(packedDirectory);

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
            if (replace) {
                if (tlas != null) tlas.close();
                if (instances != null) instances.close();
                tlas = nextTlas; instances = nextInstances; instanceCount = count;
            }
            // 发布新目录；旧的记录范围在旧目录（及其使用的 GPU 工作）完成后释放。
            var previousDirectory = directory;
            directory = nextDirectory;
            nextDirectory = null;
            if (previousDirectory != null) {
                var frees = List.copyOf(pendingRecordFrees);
                pendingRecordFrees.clear();
                previousDirectory.onDestroy(() -> frees.forEach(this::freeRecordRange));
                previousDirectory.close();
            } else {
                pendingRecordFrees.forEach(this::freeRecordRange);
                pendingRecordFrees.clear();
            }
            // 新 TLAS 已持有当前 BLAS，旧 TLAS/已录制命令通过底层租约保护旧 BLAS。
            retired.forEach(MeshResource::close);
            retired.clear();
            instanceBytes += (long) count * 64;
            directoryBytes += directorySize;
            dirty = structural = false;
            return tlas;
        } catch (RuntimeException | Error failure) {
            if (nextDirectory != null && nextDirectory != directory) nextDirectory.close();
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

    private void freeRecordRange(int[] range) {
        records.free(range[0], range[1], range[2]);
    }

    /** 保证记录缓冲能容纳 {@code bytes} 字节；不足时扩容并重新打包全部范围。 */
    private void ensureRecordCapacity(int bytes) {
        int guard = 0;
        while (!records.canAllocate(bytes)) {
            if (++guard > 8) throw new IllegalStateException("记录缓冲无法扩容");
            int capacity = Math.max(Math.multiplyExact(records.capacity(), 2),
                    Math.addExact(records.resident(), Math.addExact(bytes, 1 << 20)));
            records.reallocate(capacity);
            repackAllRecords();
        }
    }

    private void repackAllRecords() {
        for (var resource : meshes.values()) resource.invalidateRecords();
        for (var resource : meshes.values()) resource.writeRecords(records);
    }

    private void retireRecords(MeshResource resource) {
        for (int i = 0; i < resource.recordOffsets.size(); i++) {
            int offset = resource.recordOffsets.get(i);
            if (offset >= 0) pendingRecordFrees.add(new int[]{
                    offset, resource.recordSizes.get(i), resource.recordGenerations.get(i)});
        }
        resource.invalidateRecords();
    }

    @Override public void close() {
        if (tlas != null) tlas.close();
        if (instances != null) instances.close();
        if (directory != null) directory.close();
        meshes.values().forEach(MeshResource::close);
        retired.forEach(MeshResource::close);
        meshes.clear(); retired.clear();
        pendingRecordFrees.clear();
        records.close();
        tlas = null; instances = directory = null; instanceCount = -1;
        anchor = null; anchorGeneration = 0;
        dirty = structural = true;
    }

    /** anchor 取整到固定网格，避免相机轻微移动造成频繁重写。 */
    private static double quantize(double value) {
        return Math.floor(value / ANCHOR_GRID) * ANCHOR_GRID;
    }

    private final class MeshResource implements AutoCloseable {
        private final List<AccelerationStructureBuffer> vertices = new ArrayList<>(), indices = new ArrayList<>();
        private final List<TriangleMesh> topology;
        private final List<Integer> recordOffsets = new ArrayList<>();
        private final List<Integer> recordSizes = new ArrayList<>();
        private final List<Integer> recordGenerations = new ArrayList<>();
        private List<TriangleMesh> geometry;
        private final boolean dynamic;
        private AccelerationStructure blas;
        private double x, y, z;
        private boolean closed;

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
                packRecords();
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

        private void packRecords() {
            SceneAccelerationStructure.this.retireRecords(this);
            int total = 0;
            for (var mesh : geometry) total = Math.addExact(total, SceneRecordData.byteSize(mesh));
            int generation = records.generation();
            ensureRecordCapacity(total);
            // 扩容会重新打包全部范围（包括本资源），此时不再重复写入。
            if (records.generation() != generation) return;
            writeRecords(records);
        }

        private void writeRecords(SceneGeometryArena arena) {
            int[] offsets = new int[geometry.size()];
            int[] sizes = new int[geometry.size()];
            int[] generations = new int[geometry.size()];
            for (int i = 0; i < geometry.size(); i++) {
                var mesh = geometry.get(i);
                int bytes = SceneRecordData.byteSize(mesh);
                int offset = arena.allocate(bytes);
                if (offset < 0) throw new IllegalStateException("记录缓冲无法容纳网格");
                var packed = MemoryUtil.memAlloc(bytes).order(ByteOrder.nativeOrder());
                try {
                    SceneRecordData.write(packed, mesh);
                    packed.flip();
                    arena.buffer().upload(offset, packed);
                } finally {
                    MemoryUtil.memFree(packed);
                }
                offsets[i] = offset;
                sizes[i] = bytes;
                generations[i] = arena.generation();
                recordBytes += bytes;
            }
            for (int i = 0; i < geometry.size(); i++) {
                recordOffsets.add(offsets[i]);
                recordSizes.add(sizes[i]);
                recordGenerations.add(generations[i]);
            }
        }

        private void invalidateRecords() {
            recordOffsets.clear();
            recordSizes.clear();
            recordGenerations.clear();
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (blas != null) blas.close();
            vertices.forEach(AccelerationStructureBuffer::close);
            indices.forEach(AccelerationStructureBuffer::close);
            SceneAccelerationStructure.this.retireRecords(this);
        }
    }
}
