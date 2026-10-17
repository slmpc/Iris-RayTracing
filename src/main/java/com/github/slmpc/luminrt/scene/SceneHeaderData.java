package com.github.slmpc.luminrt.scene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * std430 场景头：anchor、重定位代数与实例数。
 * 由 LuminRT 按块名自动绑定为 {@code luminrt_SceneHeader}，不参与 Iris 的普通 storage 分配。
 */
public final class SceneHeaderData {
    public static final String SHADER_NAME = "luminrt_SceneHeader";
    public static final int VERSION = 1;
    /** uvec4 info + ivec4 anchor，共 32 字节。 */
    public static final int SIZE = 32;

    private SceneHeaderData() {}

    /**
     * 写入 {@code uvec4(version, anchorGeneration, instanceCount, 0)} 与 {@code ivec4(anchor.xyz, 0)}。
     * anchor 以整数发布，shader 可用 {@code cameraPositionInt - anchor} 在远坐标下保持精度。
     */
    public static void write(ByteBuffer target, SceneAccelerationStructure.Anchor anchor,
                             long anchorGeneration, int instanceCount) {
        if (target.remaining() < SIZE) throw new IllegalArgumentException("场景头缓冲区空间不足");
        target.order(ByteOrder.nativeOrder());
        target.putInt(VERSION);
        target.putInt((int) anchorGeneration);
        target.putInt(instanceCount);
        target.putInt(0);
        target.putInt((int) anchor.x());
        target.putInt((int) anchor.y());
        target.putInt((int) anchor.z());
        target.putInt(0);
    }
}
