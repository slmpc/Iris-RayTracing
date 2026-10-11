package com.github.slmpc.luminrt.rhi;

import java.nio.ByteBuffer;

/** 支持设备地址的构建输入。上传和构建使用图形命令编码器。 */
public interface AccelerationStructureBuffer extends AutoCloseable {
    long size();
    long deviceAddress();
    boolean isClosed();

    /** 录制通过暂存缓冲区上传直接缓冲区数据的命令。偏移量和字节数必须是四的倍数。 */
    void upload(long offset, ByteBuffer data);

    @Override
    void close();
}
