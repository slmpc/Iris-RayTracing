package com.github.slmpc.luminrt.rhi;

import java.util.Objects;

/** 构建输入的布局。偏移量以字节为单位；所有缓冲区必须来自同一后端。 */
public sealed interface AccelerationGeometry {
    int primitiveCount();
    boolean opaque();

    enum IndexType { NONE, UINT16, UINT32 }

    /** 顶点位置使用 R32G32B32_SFLOAT 格式。索引引用所提供的顶点范围。 */
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

    /** 每个 AABB 包含六个浮点数，依次为最小点的 xyz 和最大点的 xyz。 */
    record Aabbs(AccelerationStructureBuffer buffer, long offset, long stride,
                 int primitiveCount, boolean opaque) implements AccelerationGeometry {
        public Aabbs {
            require(primitiveCount > 0 && stride >= 24 && stride % 8 == 0, "Invalid AABB layout");
            range(buffer, offset, Math.addExact(Math.multiplyExact(primitiveCount - 1L, stride), 24), 8);
        }
    }

    /** 连续存放的 VkAccelerationStructureInstanceKHR 记录，每条 64 字节，而非指针数组。 */
    record Instances(AccelerationStructureBuffer buffer, long offset,
                     int primitiveCount) implements AccelerationGeometry {
        public Instances {
            require(primitiveCount >= 0, "Instance count must be non-negative");
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
