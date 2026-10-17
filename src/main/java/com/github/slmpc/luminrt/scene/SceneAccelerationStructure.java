package com.github.slmpc.luminrt.scene;

import com.github.slmpc.luminrt.rhi.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * 静态几何按键替换，动态几何仅在索引与布局不变时 refit；所有实例使用 anchor 相对坐标。
 *
 * <p>着色数据以 {@link SceneRecordData} 分段存放在 {@link SceneGeometryArena} 中，同时作为 BLAS
 * 顶点输入；目录只包含 header、实例与 geometry 索引，任何变化只重写变化的记录范围。
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
    /** 共享索引 pattern 的初始记录容量。 */
    private static final int INITIAL_INDEX_RECORDS = 4096;

    private final AccelerationStructureBackend backend;
    private final Map<Object, MeshResource> meshes = new LinkedHashMap<>();
    private final List<MeshResource> retired = new ArrayList<>();
    private final SceneGeometryArena records;
    private final List<int[]> pendingRecordFrees = new ArrayList<>();
    private final List<AccelerationStructureBackend.Build> pendingBuilds = new ArrayList<>();
    private final int initialIndexRecords;
    private AccelerationStructureBuffer quadIndices;
    private AccelerationStructureBuffer triangleIndices;
    private int quadIndexCapacity;
    private int triangleIndexCapacity;
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
        this(backend, INITIAL_RECORD_BYTES, INITIAL_INDEX_RECORDS);
    }

    /** 测试用：允许以较小初始容量覆盖 arena 与共享索引的扩容路径。 */
    SceneAccelerationStructure(AccelerationStructureBackend backend, int initialRecordBytes, int initialIndexRecords) {
        this.backend = Objects.requireNonNull(backend);
        this.records = new SceneGeometryArena(backend, initialRecordBytes);
        this.initialIndexRecords = Math.max(1, initialIndexRecords);
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
            // 拓扑未变时原地重写记录并 refit；记录地址不变，目录条目也无需改变。
            boolean changed = false;
            for (int i = 0; i < geometry.size(); i++)
                changed |= !previous.geometry.get(i).sameContent(geometry.get(i));
            previous.geometry = geometry;
            if (changed) previous.packRecords();
            requestBuild(previous.blas, true);
            previous.x = x; previous.y = y; previous.z = z;
            blasUpdates++;
        } else {
            var next = new MeshResource(geometry, x, y, z, dynamic);
            meshes.put(key, next);
            next.registered = true;
            if (previous != null) retired.add(previous);
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
        // BLAS 构建/更新先于 TLAS；失败时保留队列，下一次构建重试。
        flushBuilds();
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
                            | (mesh.opaque() ? SceneGeometryDirectory.FLAG_OPAQUE : 0)
                            | (resource.dynamic ? SceneGeometryDirectory.FLAG_DYNAMIC : 0);
                    geometries.add(new SceneGeometryDirectory.Geometry(
                            resource.shadingOffsets.get(i), mesh.recordCount(), flags,
                            resource.positionOffsets.get(i)));
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

    /** 记录地址随 arena 换代而变化：重新打包后所有 BLAS 必须按新地址重建。 */
    private void repackAllRecords() {
        for (var resource : meshes.values()) resource.invalidateRecords();
        for (var resource : meshes.values()) resource.writeRecords(records);
        for (var resource : meshes.values()) resource.rebuildBlas();
    }

    /** 共享索引 pattern 容量不足时扩容；地址变化后重建全部 BLAS。 */
    private void ensureIndexCapacity(boolean quads, int requested) {
        int capacity = quads ? quadIndexCapacity : triangleIndexCapacity;
        if (requested <= capacity) return;
        int next = Math.max(requested, capacity == 0
                ? initialIndexRecords
                : Math.multiplyExact(capacity, 2));
        int perRecord = quads ? 6 : 3;
        long bytes = Math.multiplyExact((long) next * perRecord, 4);
        var buffer = backend.createInputBuffer(bytes);
        var pattern = MemoryUtil.memAlloc(Math.toIntExact(bytes)).order(ByteOrder.nativeOrder());
        try {
            SceneRecordData.writeIndexPattern(pattern, quads, next);
            pattern.flip();
            buffer.upload(0, pattern);
        } finally {
            MemoryUtil.memFree(pattern);
        }
        if (quads) {
            if (quadIndices != null) quadIndices.close();
            quadIndices = buffer;
            quadIndexCapacity = next;
        } else {
            if (triangleIndices != null) triangleIndices.close();
            triangleIndices = buffer;
            triangleIndexCapacity = next;
        }
        for (var resource : meshes.values()) resource.rebuildBlas();
    }

    private void requestBuild(AccelerationStructure structure, boolean update) {
        // build 覆盖 update；已有 build 时不得降级为 update（结构可能尚未构建）。
        for (int i = 0; i < pendingBuilds.size(); i++) {
            var existing = pendingBuilds.get(i);
            if (existing.structure() != structure) continue;
            if (!existing.update()) return;
            if (update) return;
            pendingBuilds.set(i, new AccelerationStructureBackend.Build(structure, false));
            return;
        }
        pendingBuilds.add(new AccelerationStructureBackend.Build(structure, update));
    }

    private void discardPendingBuild(AccelerationStructure structure) {
        pendingBuilds.removeIf(build -> build.structure() == structure);
    }

    /** 一次提交本帧累积的 BLAS 构建/更新；失败时保留队列供下一次构建重试。 */
    private void flushBuilds() {
        if (pendingBuilds.isEmpty()) return;
        var builds = List.copyOf(pendingBuilds);
        backend.buildBatch(builds);
        pendingBuilds.clear();
    }

    private void retireRecords(MeshResource resource) {
        for (int i = 0; i < resource.shadingOffsets.size(); i++) {
            addPendingFree(resource.shadingOffsets.get(i), resource.shadingSizes.get(i),
                    resource.recordGenerations.get(i));
            addPendingFree(resource.positionOffsets.get(i), resource.positionSizes.get(i),
                    resource.recordGenerations.get(i));
        }
        resource.invalidateRecords();
    }

    private void addPendingFree(int offset, int bytes, int generation) {
        if (offset >= 0) pendingRecordFrees.add(new int[]{offset, bytes, generation});
    }

    @Override public void close() {
        pendingBuilds.clear();
        if (tlas != null) tlas.close();
        if (instances != null) instances.close();
        if (directory != null) directory.close();
        if (quadIndices != null) quadIndices.close();
        if (triangleIndices != null) triangleIndices.close();
        meshes.values().forEach(MeshResource::close);
        retired.forEach(MeshResource::close);
        meshes.clear(); retired.clear();
        pendingRecordFrees.clear();
        records.close();
        tlas = null; instances = directory = null; instanceCount = -1;
        quadIndices = triangleIndices = null;
        quadIndexCapacity = triangleIndexCapacity = 0;
        anchor = null; anchorGeneration = 0;
        dirty = structural = true;
    }

    /** anchor 取整到固定网格，避免相机轻微移动造成频繁重写。 */
    private static double quantize(double value) {
        return Math.floor(value / ANCHOR_GRID) * ANCHOR_GRID;
    }

    private final class MeshResource implements AutoCloseable {
        private final List<TriangleMesh> topology;
        private final List<Integer> shadingOffsets = new ArrayList<>();
        private final List<Integer> shadingSizes = new ArrayList<>();
        private final List<Integer> positionOffsets = new ArrayList<>();
        private final List<Integer> positionSizes = new ArrayList<>();
        private final List<Integer> recordGenerations = new ArrayList<>();
        private List<TriangleMesh> geometry;
        private final boolean dynamic;
        private AccelerationStructure blas;
        private double x, y, z;
        private boolean closed;
        /** 是否已经加入 meshes；构造期的新资源不会参与全量重打包。 */
        private boolean registered;

        private MeshResource(List<TriangleMesh> geometry, double x, double y, double z, boolean dynamic) {
            this.topology = dynamic ? geometry : List.of();
            this.dynamic = dynamic; this.x = x; this.y = y; this.z = z;
            this.geometry = geometry;
            try {
                packRecords();
                for (var mesh : geometry)
                    ensureIndexCapacity(mesh.quads(), mesh.recordCount());
                buildBlas();
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }

        private boolean sameTopology(List<TriangleMesh> geometry) {
            if (topology.size() != geometry.size()) return false;
            for (int i = 0; i < topology.size(); i++) if (!topology.get(i).sameTopology(geometry.get(i))) return false;
            return true;
        }

        private void packRecords() {
            if (writeRecordsInPlace()) return;
            SceneAccelerationStructure.this.retireRecords(this);
            int total = 0;
            for (var mesh : geometry)
                total = Math.addExact(total, Math.addExact(SceneRecordData.shadingByteSize(mesh),
                        SceneRecordData.positionByteSize(mesh)));
            int generation = records.generation();
            ensureRecordCapacity(total);
            // 扩容会重打包并重建 BLAS：已注册资源已被覆盖，新资源尚未加入 meshes，仍需写入自己的范围。
            if (records.generation() != generation && registered) return;
            writeRecords(records);
        }

        private boolean writeRecordsInPlace() {
            if (shadingOffsets.size() != geometry.size()) return false;
            for (int i = 0; i < geometry.size(); i++) {
                if (shadingSizes.get(i) != SceneRecordData.shadingByteSize(geometry.get(i))
                        || positionSizes.get(i) != SceneRecordData.positionByteSize(geometry.get(i))
                        || recordGenerations.get(i) != records.generation()) return false;
            }
            for (int i = 0; i < geometry.size(); i++)
                uploadRecord(shadingOffsets.get(i), positionOffsets.get(i), geometry.get(i));
            return true;
        }

        private void writeRecords(SceneGeometryArena arena) {
            int[] shading = new int[geometry.size()];
            int[] shadingBytes = new int[geometry.size()];
            int[] positions = new int[geometry.size()];
            int[] positionBytes = new int[geometry.size()];
            int[] generations = new int[geometry.size()];
            for (int i = 0; i < geometry.size(); i++) {
                var mesh = geometry.get(i);
                int shadings = SceneRecordData.shadingByteSize(mesh);
                int positionSize = SceneRecordData.positionByteSize(mesh);
                int shadingOffset = arena.allocate(shadings);
                int positionOffset = arena.allocate(positionSize);
                if (shadingOffset < 0 || positionOffset < 0) throw new IllegalStateException("记录缓冲无法容纳网格");
                uploadRecord(shadingOffset, positionOffset, mesh);
                shading[i] = shadingOffset;
                shadingBytes[i] = shadings;
                positions[i] = positionOffset;
                positionBytes[i] = positionSize;
                generations[i] = arena.generation();
            }
            for (int i = 0; i < geometry.size(); i++) {
                shadingOffsets.add(shading[i]);
                shadingSizes.add(shadingBytes[i]);
                positionOffsets.add(positions[i]);
                positionSizes.add(positionBytes[i]);
                recordGenerations.add(generations[i]);
            }
        }

        private void uploadRecord(int shadingOffset, int positionOffset, TriangleMesh mesh) {
            int shadings = SceneRecordData.shadingByteSize(mesh);
            var packedShading = MemoryUtil.memAlloc(shadings).order(ByteOrder.nativeOrder());
            try {
                SceneRecordData.writeShading(packedShading, mesh);
                packedShading.flip();
                records.buffer().upload(shadingOffset, packedShading);
            } finally {
                MemoryUtil.memFree(packedShading);
            }
            int positions = SceneRecordData.positionByteSize(mesh);
            var packedPositions = MemoryUtil.memAlloc(positions).order(ByteOrder.nativeOrder());
            try {
                SceneRecordData.writePositions(packedPositions, mesh);
                packedPositions.flip();
                records.buffer().upload(positionOffset, packedPositions);
            } finally {
                MemoryUtil.memFree(packedPositions);
            }
            recordBytes += (long) shadings + positions;
        }

        private void invalidateRecords() {
            shadingOffsets.clear();
            shadingSizes.clear();
            positionOffsets.clear();
            positionSizes.clear();
            recordGenerations.clear();
        }

        private void buildBlas() {
            var descriptions = new ArrayList<AccelerationGeometry>();
            for (int i = 0; i < geometry.size(); i++) {
                var mesh = geometry.get(i);
                int count = mesh.recordCount();
                var indices = mesh.quads() ? quadIndices : triangleIndices;
                if (indices == null) throw new IllegalStateException("共享索引尚未准备");
                int primitives = mesh.quads() ? Math.multiplyExact(count, 2) : count;
                descriptions.add(new AccelerationGeometry.Triangles(records.buffer(), positionOffsets.get(i),
                        Math.multiplyExact(count, SceneRecordData.VERTICES_PER_RECORD), SceneRecordData.VERTEX_STRIDE,
                        indices, 0, AccelerationGeometry.IndexType.UINT32, primitives, mesh.opaque()));
            }
            blas = backend.create(new AccelerationStructureDescription(AccelerationStructure.Type.BOTTOM_LEVEL,
                    descriptions, dynamic, !dynamic));
            requestBuild(blas, false);
            blasBuilds++;
        }

        private void rebuildBlas() {
            if (closed || shadingOffsets.size() != geometry.size()) return;
            if (blas != null) {
                SceneAccelerationStructure.this.discardPendingBuild(blas);
                blas.close();
            }
            buildBlas();
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (blas != null) {
                SceneAccelerationStructure.this.discardPendingBuild(blas);
                blas.close();
            }
            SceneAccelerationStructure.this.retireRecords(this);
        }
    }
}
