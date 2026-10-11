package com.github.slmpc.irisrt.rhi;

/** 由 GPU 构建的 BLAS 或 TLAS。关闭时安排在待处理的 GPU 工作完成后销毁。 */
public interface AccelerationStructure extends AutoCloseable {
    enum Type { BOTTOM_LEVEL, TOP_LEVEL }

    Type type();
    long deviceAddress();
    boolean isClosed();

    @Override
    void close();
}
