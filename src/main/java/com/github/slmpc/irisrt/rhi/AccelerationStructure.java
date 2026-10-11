package com.github.slmpc.irisrt.rhi;

import java.util.List;

/** 由 GPU 构建的 BLAS 或 TLAS。关闭时安排在待处理的 GPU 工作完成后销毁。 */
public interface AccelerationStructure extends AutoCloseable {
    enum Type { BOTTOM_LEVEL, TOP_LEVEL }

    Type type();
    long deviceAddress();
    boolean isClosed();

    /** TLAS 持有其引用的 BLAS；替换引用时，旧引用在已录制的 GPU 工作完成后释放。 */
    default void setReferencedStructures(List<AccelerationStructure> structures) {
        if (!structures.isEmpty()) throw new UnsupportedOperationException("Structure does not support references");
    }

    @Override
    void close();
}
