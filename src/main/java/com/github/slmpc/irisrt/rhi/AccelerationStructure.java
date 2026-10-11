package com.github.slmpc.irisrt.rhi;

/** A GPU-built BLAS or TLAS. Close queues destruction after pending GPU work. */
public interface AccelerationStructure extends AutoCloseable {
    enum Type { BOTTOM_LEVEL, TOP_LEVEL }

    Type type();
    long deviceAddress();
    boolean isClosed();

    @Override
    void close();
}
