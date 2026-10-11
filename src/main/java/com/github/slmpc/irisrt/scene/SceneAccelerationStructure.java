package com.github.slmpc.irisrt.scene;

import com.github.slmpc.irisrt.rhi.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteOrder;
import java.util.*;

/** 静态几何按键替换，动态几何仅在索引与布局不变时 refit，所有实例使用相机相对坐标。 */
public final class SceneAccelerationStructure implements AutoCloseable {
    private final AccelerationStructureBackend backend;
    private final Map<Object, MeshResource> meshes = new LinkedHashMap<>();
    private final List<MeshResource> retired = new ArrayList<>();
    private AccelerationStructure tlas;
    private AccelerationStructureBuffer instances;
    private int instanceCount = -1;
    private boolean dirty = true;
    private boolean structural = true;
    private double cameraX, cameraY, cameraZ;
    private long blasBuilds, blasUpdates, tlasBuilds, tlasUpdates;

    public SceneAccelerationStructure(AccelerationStructureBackend backend) {
        this.backend = Objects.requireNonNull(backend);
    }

    public record Statistics(int meshes, long blasBuilds, long blasUpdates, long tlasBuilds, long tlasUpdates) {}
    public Statistics statistics() { return new Statistics(meshes.size(), blasBuilds, blasUpdates, tlasBuilds, tlasUpdates); }

    public void put(Object key, List<TriangleMesh> geometry, double x, double y, double z, boolean dynamic) {
        Objects.requireNonNull(key);
        geometry = List.copyOf(geometry);
        if (geometry.isEmpty()) { remove(key); return; }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("Non-finite origin");
        var previous = meshes.get(key);
        dirty = true;
        if (dynamic && previous != null && previous.dynamic && previous.sameTopology(geometry)) {
            previous.upload(geometry, false);
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
        if (!dirty && cameraX == x && cameraY == y && cameraZ == z) return tlas;
        int count = meshes.size();
        if (count > 0x1000000) throw new IllegalStateException("Instance custom index exceeds 24 bits");
        var data = MemoryUtil.memCalloc(Math.multiplyExact(Math.max(1, count), 64)).order(ByteOrder.nativeOrder());
        AccelerationStructure nextTlas = tlas;
        AccelerationStructureBuffer nextInstances = instances;
        boolean replace = tlas == null || instanceCount != count;
        try {
            int index = 0;
            for (var resource : meshes.values()) {
                int offset = index * 64;
                data.putFloat(offset, 1).putFloat(offset + 12, (float)(resource.x - x));
                data.putFloat(offset + 20, 1).putFloat(offset + 28, (float)(resource.y - y));
                data.putFloat(offset + 40, 1).putFloat(offset + 44, (float)(resource.z - z));
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
            nextInstances.upload(0, data);
            if (!replace && !structural && count != 0) { backend.update(nextTlas); tlasUpdates++; }
            else { backend.build(nextTlas); tlasBuilds++; }
            nextTlas.setReferencedStructures(meshes.values().stream().map(resource -> resource.blas).toList());
            if (replace) {
                if (tlas != null) tlas.close();
                if (instances != null) instances.close();
                tlas = nextTlas; instances = nextInstances; instanceCount = count;
            }
            // 新 TLAS 已持有当前 BLAS，旧 TLAS/已录制命令通过底层租约保护旧 BLAS。
            retired.forEach(MeshResource::close);
            retired.clear();
            cameraX = x; cameraY = y; cameraZ = z;
            dirty = structural = false;
            return tlas;
        } catch (RuntimeException | Error failure) {
            if (replace) {
                if (nextTlas != null && nextTlas != tlas) nextTlas.close();
                if (nextInstances != null && nextInstances != instances) nextInstances.close();
            }
            throw failure;
        } finally { MemoryUtil.memFree(data); }
    }

    @Override public void close() {
        if (tlas != null) tlas.close();
        if (instances != null) instances.close();
        meshes.values().forEach(MeshResource::close);
        retired.forEach(MeshResource::close);
        meshes.clear(); retired.clear();
        tlas = null; instances = null; instanceCount = -1; dirty = structural = true;
    }

    private final class MeshResource implements AutoCloseable {
        private final List<AccelerationStructureBuffer> vertices = new ArrayList<>(), indices = new ArrayList<>();
        private final List<TriangleMesh> topology;
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
            for (int i = 0; i < geometry.size(); i++) {
                var mesh = geometry.get(i);
                var data = MemoryUtil.memAlloc(Math.multiplyExact(mesh.vertexCount(), 12)).order(ByteOrder.nativeOrder());
                try { mesh.writePositions(data); vertices.get(i).upload(0, data); }
                finally { MemoryUtil.memFree(data); }
                if (includeIndices) {
                    data = MemoryUtil.memAlloc(Math.multiplyExact(mesh.triangleCount(), 12)).order(ByteOrder.nativeOrder());
                    try { mesh.writeIndices(data); indices.get(i).upload(0, data); }
                    finally { MemoryUtil.memFree(data); }
                }
            }
        }

        @Override public void close() {
            if (blas != null) blas.close();
            vertices.forEach(AccelerationStructureBuffer::close);
            indices.forEach(AccelerationStructureBuffer::close);
        }
    }
}
