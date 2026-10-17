package com.github.slmpc.luminrt.scene;

import com.github.slmpc.luminrt.rhi.AccelerationStructure;
import com.github.slmpc.luminrt.rhi.AccelerationStructureBackend;
import com.github.slmpc.luminrt.rhi.AccelerationStructureBuffer;
import com.github.slmpc.luminrt.rhi.AccelerationStructureDescription;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SceneGeometryArenaTest {
    @Test
    void allocatesAlignedRangesAndReusesFreedHoles() {
        var backend = new Backend();
        try (var arena = new SceneGeometryArena(backend, 4096)) {
            assertEquals(0, arena.allocate(112));
            assertEquals(112, arena.allocate(112));
            assertEquals(224, arena.allocate(112));
            assertEquals(336, arena.resident());
            arena.free(112, 112, arena.generation());
            assertEquals(224, arena.resident());
            // 空洞优先复用，不继续增长。
            assertEquals(112, arena.allocate(112));
            assertEquals(336, arena.resident());
            assertEquals(-1, arena.allocate(16 * 1024));
        }
    }

    @Test
    void coalescesAdjacentHolesAndIgnoresStaleGeneration() {
        var backend = new Backend();
        try (var arena = new SceneGeometryArena(backend, 4096)) {
            arena.allocate(112);
            arena.allocate(112);
            arena.allocate(112);
            int generation = arena.generation();
            arena.free(0, 112, generation);
            arena.free(112, 112, generation);
            // 两个相邻空洞合并后可直接满足 224 的请求。
            assertEquals(0, arena.allocate(224));
            assertEquals(336, arena.resident());

            arena.reallocate(8192);
            int nextGeneration = arena.generation();
            assertNotEquals(generation, nextGeneration);
            assertEquals(0, arena.resident());
            // 旧代数的释放请求随旧缓冲一起退休，不得写回新链表。
            arena.free(0, 112, generation);
            assertEquals(0, arena.resident());
            assertEquals(0, arena.allocate(112));

            assertThrows(IllegalArgumentException.class, () -> arena.free(1, 112, nextGeneration));
            assertThrows(IllegalArgumentException.class, () -> arena.allocate(100));
        }
    }

    @Test
    void reallocateClosesPreviousBufferAndResetsState() {
        var backend = new Backend();
        var arena = new SceneGeometryArena(backend, 4096);
        int generation = arena.generation();
        arena.allocate(112);
        arena.reallocate(16384);
        assertTrue(backend.buffers.getFirst().closed);
        assertFalse(backend.buffers.getLast().closed);
        assertEquals(0, arena.resident());
        assertEquals(generation + 1, arena.generation());
        arena.close();
        assertTrue(backend.buffers.getLast().closed);
        assertThrows(IllegalStateException.class, arena::capacity);
    }

    private static final class Backend implements AccelerationStructureBackend {
        final List<Buffer> buffers = new ArrayList<>();

        @Override public boolean isSupported() { return true; }

        @Override public AccelerationStructureBuffer createInputBuffer(long size) {
            var buffer = new Buffer(size);
            buffers.add(buffer);
            return buffer;
        }

        @Override public AccelerationStructure create(AccelerationStructureDescription description) {
            throw new UnsupportedOperationException();
        }

        @Override public void build(AccelerationStructure structure) { throw new UnsupportedOperationException(); }

        @Override public void update(AccelerationStructure structure) { throw new UnsupportedOperationException(); }
    }

    private static final class Buffer implements AccelerationStructureBuffer {
        private final long size;
        private boolean closed;

        Buffer(long size) { this.size = size; }

        @Override public long size() { return size; }
        @Override public long deviceAddress() { return 0x1000; }
        @Override public boolean isClosed() { return closed; }
        @Override public void upload(long offset, ByteBuffer data) { throw new UnsupportedOperationException(); }
        @Override public void close() { closed = true; }
    }
}
