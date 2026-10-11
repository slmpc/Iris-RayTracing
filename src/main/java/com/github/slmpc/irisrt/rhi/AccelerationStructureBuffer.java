package com.github.slmpc.irisrt.rhi;

import java.nio.ByteBuffer;

/** Device-addressable build input. Uploads and builds use the graphics command encoder. */
public interface AccelerationStructureBuffer extends AutoCloseable {
    long size();
    long deviceAddress();
    boolean isClosed();

    /** Records a staging upload from a direct buffer. Offset and byte count must be multiples of four. */
    void upload(long offset, ByteBuffer data);

    @Override
    void close();
}
