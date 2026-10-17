package com.github.slmpc.luminrt.scene;

import com.github.slmpc.luminrt.rhi.AccelerationStructureBackend;
import com.github.slmpc.luminrt.rhi.AccelerationStructureBuffer;

import java.util.Map;
import java.util.TreeMap;

/**
 * 16 B 对齐的分段几何缓冲。
 *
 * <p>范围在 GPU 完成证明后才释放；缓冲换代时旧范围随旧缓冲一起退休，
 * 之后到达的旧代数释放请求被忽略，避免把旧偏移写回新的空闲链表。
 */
final class SceneGeometryArena implements AutoCloseable {
    private final AccelerationStructureBackend backend;
    private final TreeMap<Integer, Integer> free = new TreeMap<>();
    private AccelerationStructureBuffer buffer;
    private int capacity;
    private int generation;
    private int reserved;
    private int resident;
    private boolean closed;

    SceneGeometryArena(AccelerationStructureBackend backend, int initialCapacity) {
        this.backend = backend;
        this.capacity = Math.max(4096, roundUp16(initialCapacity));
        this.buffer = backend.createInputBuffer(capacity);
    }

    /** 分配失败返回 -1；调用方负责换用更大缓冲并重新打包。 */
    int allocate(int bytes) {
        requireOpen();
        if (bytes <= 0 || (bytes & 15) != 0) throw new IllegalArgumentException("记录范围必须按 16 B 对齐");
        for (Map.Entry<Integer, Integer> entry : free.entrySet()) {
            if (entry.getValue() < bytes) continue;
            int offset = entry.getKey();
            int remaining = entry.getValue() - bytes;
            free.remove(offset);
            if (remaining > 0) free.put(offset + bytes, remaining);
            resident += bytes;
            return offset;
        }
        if (capacity - reserved >= bytes) {
            int offset = reserved;
            reserved += bytes;
            resident += bytes;
            return offset;
        }
        return -1;
    }

    /** 只接受当前缓冲代数的范围；旧代数的释放由缓冲整体退休处理。 */
    void free(int offset, int bytes, int rangeGeneration) {
        if (closed || rangeGeneration != generation) return;
        if (offset < 0 || bytes <= 0 || (offset & 15) != 0 || (bytes & 15) != 0
                || offset + bytes > reserved) {
            throw new IllegalArgumentException("非法记录范围");
        }
        resident -= bytes;
        int start = offset;
        int length = bytes;
        var lower = free.floorEntry(start);
        if (lower != null && lower.getKey() + lower.getValue() == start) {
            start = lower.getKey();
            length += lower.getValue();
            free.remove(lower.getKey());
        }
        Integer higher = free.remove(start + length);
        if (higher != null) length += higher;
        free.put(start, length);
    }

    /** 换用更大缓冲；旧缓冲延迟销毁，旧范围不再参与空闲管理。 */
    void reallocate(int targetCapacity) {
        requireOpen();
        int nextCapacity = Math.max(capacity * 2, roundUp16(targetCapacity));
        var next = backend.createInputBuffer(nextCapacity);
        buffer.close();
        buffer = next;
        capacity = nextCapacity;
        generation++;
        reserved = 0;
        resident = 0;
        free.clear();
    }

    int generation() { return generation; }

    AccelerationStructureBuffer buffer() {
        requireOpen();
        return buffer;
    }

    long deviceAddress() {
        requireOpen();
        return buffer.deviceAddress();
    }

    int capacity() {
        requireOpen();
        return capacity;
    }

    int resident() {
        requireOpen();
        return resident;
    }

    int freeBytes() {
        requireOpen();
        return capacity - resident;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        buffer.close();
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("几何缓冲已关闭");
    }

    static int roundUp16(int value) {
        return (value + 15) & ~15;
    }
}
