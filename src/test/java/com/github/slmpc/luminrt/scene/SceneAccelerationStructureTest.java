package com.github.slmpc.luminrt.scene;

import com.github.slmpc.luminrt.rhi.*;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SceneAccelerationStructureTest {
    @Test
    void triangulatesQuadsStripsAndFansWithConsistentWinding() {
        assertArrayEquals(new int[]{0, 1, 2, 2, 3, 0, 4, 5, 6, 6, 7, 4}, TriangleMesh.sequentialIndices(PrimitiveTopology.QUADS, 8));
        assertArrayEquals(new int[]{0, 1, 2, 2, 1, 3, 2, 3, 4}, TriangleMesh.sequentialIndices(PrimitiveTopology.TRIANGLE_STRIP, 5));
        assertArrayEquals(new int[]{0, 1, 2, 0, 2, 3}, TriangleMesh.sequentialIndices(PrimitiveTopology.TRIANGLE_FAN, 4));
        assertArrayEquals(new int[0], TriangleMesh.sequentialIndices(PrimitiveTopology.LINES, 4));
    }

    @Test
    void ownsInputsAndRejectsInactiveOrInvalidTriangles() {
        float[] xyz = {0, 0, 0, 1, 0, 0, 0, 1, 0};
        int[] indices = {0, 1, 2};
        var mesh = new TriangleMesh(xyz, indices, true);
        xyz[0] = 9; indices[0] = 8;
        mesh.positions()[0] = 12; mesh.indices()[0] = 12;
        assertEquals(0, mesh.positions()[0]);
        assertEquals(0, mesh.indices()[0]);
        assertThrows(IllegalArgumentException.class, () -> new TriangleMesh(new float[]{Float.NaN, 0, 0}, new int[]{0, 0, 0}, false));
        assertThrows(IllegalArgumentException.class, () -> new TriangleMesh(new float[]{0, 0, 0}, new int[]{1, 0, 0}, false));
    }

    @Test
    void retainsStaticBlasAndOnlyRebasesOutsideAnchorRadius() {
        var backend = new TestBackend();
        try (var scene = new SceneAccelerationStructure(backend)) {
            scene.put("terrain", List.of(triangle(0)), 30_000_000, 64, -30_000_000, false);
            var first = (TestStructure)scene.build(29_999_999.75, 63, -29_999_998);
            var blas = first.references.getFirst();
            var anchor = scene.anchor();
            assertNotNull(anchor);
            var input = ((TestBuffer)((AccelerationGeometry.Instances)first.description.geometries().getFirst()).buffer()).data;
            assertEquals((float)(30_000_000 - anchor.x()), input.getFloat(12), 0.0f);
            assertEquals((float)(64 - anchor.y()), input.getFloat(28), 0.0f);
            assertEquals((float)(-30_000_000 - anchor.z()), input.getFloat(44), 0.0f);
            assertEquals(blas.deviceAddress(), input.getLong(56));
            assertEquals(0xff, input.getInt(48) >>> 24);
            assertEquals(1, input.getInt(52) >>> 24);
            var before = scene.statistics();
            // 相机平移但仍在 anchor 半径内：不重写实例，也不重建 TLAS。
            assertSame(first, scene.build(30_000_000, 63, -29_999_998));
            assertEquals(before, scene.statistics());
            assertEquals(0, scene.statistics().anchorRebases());
            // 越过半径：anchor 变化，重写实例并更新 TLAS。
            assertSame(first, scene.build(30_000_000 + 300, 63, -29_999_998));
            assertEquals(1, scene.statistics().anchorRebases());
            assertEquals(1, scene.statistics().tlasUpdates());
            assertEquals(1, scene.statistics().tlasBuilds());
            // 几何变化：重建 BLAS 与 TLAS，旧 BLAS 关闭。
            scene.put("terrain", List.of(triangle(2)), 30_000_000, 64, -30_000_000, false);
            assertSame(first, scene.build(30_000_000 + 300, 63, -29_999_998));
            assertNotSame(blas, first.references.getFirst());
            assertTrue(blas.isClosed());
            assertEquals(2, scene.statistics().tlasBuilds());
            assertEquals(2, scene.statistics().blasBuilds());
        }
        assertTrue(backend.structures.stream().allMatch(TestStructure::isClosed));
        assertTrue(backend.buffers.stream().allMatch(TestBuffer::isClosed));
    }

    @Test
    void refitsFiniteDynamicVerticesButRebuildsChangedIndicesOrGeometryCounts() {
        var backend = new TestBackend();
        try (var scene = new SceneAccelerationStructure(backend)) {
            scene.put("entity", List.of(triangle(0)), 0, 0, 0, true);
            var tlas = (TestStructure)scene.build(0, 0, 0);
            var original = (TestStructure)tlas.references.getFirst();
            scene.put("entity", List.of(triangle(3)), 0, 0, 0, true);
            scene.build(0, 0, 0);
            assertSame(original, tlas.references.getFirst());
            assertEquals(1, original.updates);
            var vertexInput = (TestBuffer)((AccelerationGeometry.Triangles)original.description.geometries().getFirst()).vertices();
            assertEquals(3, vertexInput.data.getFloat(0));
            assertEquals(1, scene.statistics().tlasUpdates());
            var changed = new TriangleMesh(triangle(3).positions(), new int[]{0, 2, 1}, true);
            scene.put("entity", List.of(changed), 0, 0, 0, true);
            scene.build(0, 0, 0);
            assertNotSame(original, tlas.references.getFirst());
            assertEquals(2, scene.statistics().tlasBuilds());
            scene.put("entity", List.of(triangle(3), triangle(5)), 0, 0, 0, true);
            scene.build(0, 0, 0);
            assertEquals(3, scene.statistics().blasBuilds());
        }
    }

    @Test
    void buildsAnEmptySceneAndRemovesLastInstanceWithoutAnInactiveSentinel() {
        var backend = new TestBackend();
        try (var scene = new SceneAccelerationStructure(backend)) {
            var empty = (TestStructure)scene.build(0, 0, 0);
            assertEquals(0, empty.description.geometries().getFirst().primitiveCount());
            scene.put("block", List.of(triangle(0)), 0, 0, 0, false);
            var populated = (TestStructure)scene.build(0, 0, 0);
            assertTrue(empty.closed);
            assertEquals(1, populated.references.size());
            scene.remove("block");
            var again = (TestStructure)scene.build(0, 0, 0);
            assertEquals(0, again.description.geometries().getFirst().primitiveCount());
            assertTrue(populated.closed);
            assertTrue(populated.references.getFirst().isClosed());
            assertEquals(0, scene.statistics().meshes());
        }
    }

    @Test
    void batchesDirectoryAndInstanceUpload() {
        var backend = new TestBackend();
        try (var scene = new SceneAccelerationStructure(backend)) {
            scene.put("terrain", List.of(triangle(0)), 0, 0, 0, false);
            scene.build(0, 0, 0);
            // 第一次批量为网格记录上传，第二次为目录与实例。
            assertEquals(2, backend.uploadBatches);
            assertEquals(2, backend.lastUploadCount);
        }
    }

    @Test
    void cameraMovementWithinAnchorDoesNotRebuildOrUpload() {
        var backend = new TestBackend();
        try (var scene = new SceneAccelerationStructure(backend)) {
            scene.put("terrain", List.of(triangle(0)), 1000, 0, 0, false);
            var tlas = (TestStructure)scene.build(0, 0, 0);
            var builds = scene.statistics().tlasBuilds();
            var batches = backend.uploadBatches;
            var bytes = scene.statistics().instanceBytes();
            scene.build(100, 0, 0);
            scene.build(256, 0, 0);
            assertEquals(builds, scene.statistics().tlasBuilds());
            assertEquals(batches, backend.uploadBatches);
            assertEquals(bytes, scene.statistics().instanceBytes());
            assertSame(tlas, scene.build(256, 0, 0));
        }
        assertTrue(backend.buffers.stream().allMatch(TestBuffer::isClosed));
    }

    @Test
    void directoryCarriesAnchorCountsAndRecordRanges() {
        var backend = new TestBackend();
        try (var scene = new SceneAccelerationStructure(backend)) {
            scene.put("terrain", List.of(triangle(0)), 100, 0, 0, false);
            scene.build(10, 0, 0);
            var directory = (TestBuffer)scene.directoryBuffer();
            assertEquals(SceneGeometryDirectory.VERSION, directory.data.getInt(0));
            assertEquals((int)scene.anchorGeneration(), directory.data.getInt(4));
            assertEquals(1, directory.data.getInt(8));
            assertEquals(1, directory.data.getInt(12));
            assertEquals((int)scene.anchor().x(), directory.data.getInt(16));
            assertEquals((int)scene.anchor().y(), directory.data.getInt(20));
            assertEquals((int)scene.anchor().z(), directory.data.getInt(24));
            // 实例目录：geometry 从第 2 条开始，共 1 条。
            assertEquals(0, directory.data.getInt(2 * 16));
            assertEquals(1, directory.data.getInt(2 * 16 + 4));
            // geometry 目录：记录偏移、记录数、标志。
            int entry = 2 + 1;
            int offset = directory.data.getInt(entry * 16);
            assertEquals(1, directory.data.getInt(entry * 16 + 4));
            assertEquals(0, directory.data.getInt(entry * 16 + 8) & SceneGeometryDirectory.FLAG_QUADS);
            var records = (TestBuffer)scene.recordsBuffer();
            assertEquals(0f, records.data.getFloat(offset), 0f);
            assertTrue(scene.statistics().directoryBytes() >= SceneGeometryDirectory.byteSize(1, 1));
            assertTrue(scene.statistics().recordBytes() >= SceneRecordData.STRIDE);
            // 重定位后新的目录缓冲区携带新 anchor 与代数。
            scene.build(10 + 300, 0, 0);
            var updated = (TestBuffer)scene.directoryBuffer();
            assertNotSame(directory, updated);
            assertTrue(directory.closed);
            assertEquals((int)scene.anchorGeneration(), updated.data.getInt(4));
            assertEquals((int)scene.anchor().x(), updated.data.getInt(16));
        }
    }

    @Test
    void failedReplacementReleasesNewResourcesAndCanBeRetried() {
        var backend = new TestBackend();
        try (var scene = new SceneAccelerationStructure(backend)) {
            var original = scene.build(0, 0, 0);
            scene.put("terrain", List.of(triangle(0)), 0, 0, 0, false);
            backend.failNextBuild = true;
            assertThrows(IllegalStateException.class, () -> scene.build(0, 0, 0));
            assertFalse(original.isClosed());
            assertTrue(backend.structures.getLast().isClosed());
            assertTrue(backend.buffers.getLast().isClosed());
            var recovered = scene.build(0, 0, 0);
            assertNotSame(original, recovered);
            assertTrue(original.isClosed());
        }
    }

    private static TriangleMesh triangle(float x) {
        return new TriangleMesh(new float[]{x, 0, 0, x + 1, 0, 0, x, 1, 0}, new int[]{0, 1, 2}, true);
    }

    @Test
    void repacksDynamicRecordsOnlyWhenContentChanges() {
        var backend = new TestBackend();
        try (var scene = new SceneAccelerationStructure(backend)) {
            var mesh = new TriangleMesh(triangle(0).positions(), new int[]{0, 1, 2},
                    new float[]{0, 0, 1, 0, 0, 1}, true);
            scene.put("dynamic", List.of(mesh), 0, 0, 0, true);
            scene.build(0, 0, 0);
            int first = recordOffset(scene);
            var moved = new TriangleMesh(triangle(1).positions(), mesh.indices(), mesh.uvs(), true);
            scene.put("dynamic", List.of(moved), 0, 0, 0, true);
            scene.build(0, 0, 0);
            int second = recordOffset(scene);
            // 位置变化需要新记录范围；旧范围在旧目录退休后释放。
            assertNotEquals(first, second);
            assertEquals(1f, ((TestBuffer)scene.recordsBuffer()).data.getFloat(second), 0f);
            // 内容完全相同时只 refit，不重新打包记录。
            int batches = backend.uploadBatches;
            scene.put("dynamic", List.of(moved), 0, 0, 0, true);
            scene.build(0, 0, 0);
            assertEquals(second, recordOffset(scene));
            // 位置上传一批 + 目录/实例一批，记录未重写。
            assertEquals(batches + 2, backend.uploadBatches);
        }
        assertTrue(backend.buffers.stream().allMatch(TestBuffer::isClosed));
    }

    /** 读取唯一实例中第 {@code geometryIndex} 个 geometry 的记录起始偏移。 */
    private static int recordOffset(SceneAccelerationStructure scene) {
        var directory = ((TestBuffer)scene.directoryBuffer()).data;
        int instances = directory.getInt(8);
        return directory.getInt((2 + instances) * 16);
    }

    private static final class TestBackend implements AccelerationStructureBackend {
        final List<TestBuffer> buffers = new ArrayList<>();
        final List<TestStructure> structures = new ArrayList<>();
        boolean failNextBuild;
        int uploadBatches;
        int lastUploadCount;
        public boolean isSupported() { return true; }
        public AccelerationStructureBuffer createInputBuffer(long size) {
            var buffer = new TestBuffer(size); buffers.add(buffer); return buffer;
        }
        public AccelerationStructure create(AccelerationStructureDescription description) {
            var structure = new TestStructure(description, structures.size() + 1L); structures.add(structure); return structure;
        }
        public void build(AccelerationStructure structure) {
            if (failNextBuild) { failNextBuild = false; throw new IllegalStateException("Simulated build failure"); }
            var value = (TestStructure)structure;
            assertFalse(value.closed); value.built = true;
        }
        public void update(AccelerationStructure structure) {
            var value = (TestStructure)structure;
            assertTrue(value.built && value.description.allowUpdate() && !value.closed); value.updates++;
        }
        @Override public void upload(List<Upload> uploads) {
            uploadBatches++;
            lastUploadCount = uploads.size();
            AccelerationStructureBackend.super.upload(uploads);
        }
    }

    private static final class TestStructure implements AccelerationStructure {
        final AccelerationStructureDescription description;
        final long address;
        List<AccelerationStructure> references = List.of();
        boolean closed, built;
        int updates;
        TestStructure(AccelerationStructureDescription description, long address) { this.description = description; this.address = address; }
        public Type type() { return description.type(); }
        public long deviceAddress() { assertFalse(closed); return address; }
        public boolean isClosed() { return closed; }
        public void setReferencedStructures(List<AccelerationStructure> structures) { references = List.copyOf(structures); }
        public void close() { closed = true; }
    }

    private static final class TestBuffer implements AccelerationStructureBuffer {
        final ByteBuffer data;
        boolean closed;
        TestBuffer(long size) { data = ByteBuffer.allocate(Math.toIntExact(size)).order(ByteOrder.nativeOrder()); }
        public long size() { return data.capacity(); }
        public long deviceAddress() { return 0x1000; }
        public boolean isClosed() { return closed; }
        public void upload(long offset, ByteBuffer source) { assertFalse(closed); data.duplicate().position(Math.toIntExact(offset)).put(source.duplicate()); }
        public void close() { closed = true; }
    }
}
