package com.github.slmpc.irisrt.rhi;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccelerationGeometryTest {
    @Test
    void rejectsOutOfBoundsMisalignedAndClosedInputs() {
        var buffer = new TestBuffer(128);
        assertThrows(IllegalArgumentException.class, () -> new AccelerationGeometry.Instances(buffer, 4, 1));
        assertThrows(IllegalArgumentException.class, () -> new AccelerationGeometry.Instances(buffer, 64, 2));
        assertThrows(IllegalArgumentException.class, () -> new AccelerationGeometry.Aabbs(buffer, 0, 28, 1, true));
        assertThrows(IllegalArgumentException.class, () -> triangles(buffer, 2, 3, 12));
        buffer.close();
        assertThrows(IllegalArgumentException.class, () -> new AccelerationGeometry.Instances(buffer, 0, 1));
    }

    @Test
    void validatesIndexedAndNonIndexedTriangleRanges() {
        var vertices = new TestBuffer(36);
        var indices = new TestBuffer(6);
        assertDoesNotThrow(() -> new AccelerationGeometry.Triangles(vertices, 0, 3, 12,
                indices, 0, AccelerationGeometry.IndexType.UINT16, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new AccelerationGeometry.Triangles(vertices, 0, 3, 12,
                indices, 0, AccelerationGeometry.IndexType.UINT32, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new AccelerationGeometry.Triangles(vertices, 0, 3, 12,
                null, 0, AccelerationGeometry.IndexType.NONE, 2, true));
        assertThrows(ArithmeticException.class, () -> triangles(new TestBuffer(Long.MAX_VALUE), 0, 3, Long.MAX_VALUE - 3));
    }

    @Test
    void allowsLastPackedElementWithoutTrailingStridePadding() {
        assertDoesNotThrow(() -> triangles(new TestBuffer(44), 0, 3, 16));
        assertDoesNotThrow(() -> new AccelerationGeometry.Aabbs(new TestBuffer(56), 0, 32, 2, false));
        assertDoesNotThrow(() -> new AccelerationGeometry.Instances(new TestBuffer(128), 0, 2));
    }

    @Test
    void enforcesBlasAndTlasGeometryContractsAndCopiesTheInputList() {
        var triangle = triangles(new TestBuffer(36), 0, 3, 12);
        var instances = new AccelerationGeometry.Instances(new TestBuffer(64), 0, 1);
        var aabbs = new AccelerationGeometry.Aabbs(new TestBuffer(24), 0, 24, 1, true);
        assertThrows(IllegalArgumentException.class, () -> description(AccelerationStructure.Type.TOP_LEVEL, List.of(triangle)));
        assertThrows(IllegalArgumentException.class, () -> description(AccelerationStructure.Type.BOTTOM_LEVEL, List.of(instances)));
        assertThrows(IllegalArgumentException.class, () -> description(AccelerationStructure.Type.BOTTOM_LEVEL, List.of(triangle, aabbs)));
        assertThrows(IllegalArgumentException.class, () -> description(AccelerationStructure.Type.TOP_LEVEL, List.of(instances, instances)));
        assertThrows(IllegalArgumentException.class, () -> description(AccelerationStructure.Type.BOTTOM_LEVEL, List.of()));
        var mutable = new ArrayList<AccelerationGeometry>(List.of(triangle));
        var description = description(AccelerationStructure.Type.BOTTOM_LEVEL, mutable);
        mutable.clear();
        assertEquals(List.of(triangle), description.geometries());
        assertThrows(UnsupportedOperationException.class, () -> description.geometries().clear());
    }

    private AccelerationGeometry.Triangles triangles(TestBuffer buffer, long offset, int count, long stride) {
        return new AccelerationGeometry.Triangles(buffer, offset, count, stride, null, 0,
                AccelerationGeometry.IndexType.NONE, 1, true);
    }

    private AccelerationStructureDescription description(AccelerationStructure.Type type, List<AccelerationGeometry> geometry) {
        return new AccelerationStructureDescription(type, geometry, true, true);
    }

    private static final class TestBuffer implements AccelerationStructureBuffer {
        private final long size;
        private boolean closed;

        TestBuffer(long size) { this.size = size; }
        public long size() { return size; }
        public long deviceAddress() { return 0x1000; }
        public boolean isClosed() { return closed; }
        public void upload(long offset, ByteBuffer data) { throw new UnsupportedOperationException(); }
        public void close() { closed = true; }
    }
}
