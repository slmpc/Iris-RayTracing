package com.github.slmpc.irisrt.rhi;

import java.util.Objects;

/** Build input layouts. Offsets are bytes; all buffers must come from the same backend. */
public sealed interface AccelerationGeometry {
    int primitiveCount();
    boolean opaque();

    enum IndexType { NONE, UINT16, UINT32 }

    /** Positions are R32G32B32_SFLOAT. Indices reference the supplied vertex range. */
    record Triangles(AccelerationStructureBuffer vertices, long vertexOffset, int vertexCount,
                     long vertexStride, AccelerationStructureBuffer indices, long indexOffset,
                     IndexType indexType, int primitiveCount, boolean opaque) implements AccelerationGeometry {
        public Triangles {
            Objects.requireNonNull(vertices, "vertices");
            Objects.requireNonNull(indexType, "indexType");
            require(vertexCount > 0 && vertexStride >= 12 && vertexStride % 4 == 0, "Invalid vertex layout");
            require(primitiveCount > 0, "Triangle count must be positive");
            range(vertices, vertexOffset, Math.addExact(Math.multiplyExact(vertexCount - 1L, vertexStride), 12), 4);
            if (indexType == IndexType.NONE) {
                require(indices == null && indexOffset == 0, "Non-indexed geometry cannot have an index buffer");
                require(Math.multiplyExact(primitiveCount, 3L) <= vertexCount, "Insufficient vertices");
            } else {
                Objects.requireNonNull(indices, "indices");
                int width = indexType == IndexType.UINT16 ? 2 : 4;
                range(indices, indexOffset, Math.multiplyExact(primitiveCount * 3L, width), width);
            }
        }
    }

    /** Each AABB contains six floats (minimum xyz, maximum xyz). */
    record Aabbs(AccelerationStructureBuffer buffer, long offset, long stride,
                 int primitiveCount, boolean opaque) implements AccelerationGeometry {
        public Aabbs {
            require(primitiveCount > 0 && stride >= 24 && stride % 8 == 0, "Invalid AABB layout");
            range(buffer, offset, Math.addExact(Math.multiplyExact(primitiveCount - 1L, stride), 24), 8);
        }
    }

    /** Packed VkAccelerationStructureInstanceKHR records (64 bytes each), not an array of pointers. */
    record Instances(AccelerationStructureBuffer buffer, long offset,
                     int primitiveCount) implements AccelerationGeometry {
        public Instances {
            require(primitiveCount > 0, "Instance count must be positive");
            range(buffer, offset, Math.multiplyExact(primitiveCount, 64L), 16);
        }

        @Override
        public boolean opaque() { return false; }
    }

    private static void range(AccelerationStructureBuffer buffer, long offset, long bytes, int alignment) {
        Objects.requireNonNull(buffer, "buffer");
        require(!buffer.isClosed(), "Build input is closed");
        require(offset >= 0 && offset % alignment == 0 && bytes <= buffer.size()
                && offset <= buffer.size() - bytes, "Build input range or alignment is invalid");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
