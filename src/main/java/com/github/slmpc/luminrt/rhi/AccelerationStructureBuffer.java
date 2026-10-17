package com.github.slmpc.luminrt.rhi;

import java.nio.ByteBuffer;

/** 支持设备地址的构建输入。上传和构建使用图形命令编码器。 */
public interface AccelerationStructureBuffer extends AutoCloseable {
    long size();
    long deviceAddress();
    boolean isClosed();

    /** 返回用于只读 shader 存储表的切片，仅支持提供此能力的图形后端。 */
    default com.mojang.renderpearl.api.buffers.GpuBufferSlice shaderSlice() {
        throw new UnsupportedOperationException("Shader storage slices are not supported");
    }

    /** 录制通过暂存缓冲区上传直接缓冲区数据的命令。偏移量和字节数必须是四的倍数。 */
    void upload(long offset, ByteBuffer data);

    /**
     * 注册底层缓冲真正销毁时执行的动作（例如释放引用它的记录范围）。
     * 默认实现立即执行，表示该实现没有可证明的 GPU 完成点。
     */
    default void onDestroy(Runnable action) {
        action.run();
    }

    @Override
    void close();
}
