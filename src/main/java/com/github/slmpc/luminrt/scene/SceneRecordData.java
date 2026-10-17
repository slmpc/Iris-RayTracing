package com.github.slmpc.luminrt.scene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 场景记录布局：每条 112 B（7 个 {@code uvec4}），同时供 BLAS 顶点输入与 shader 解包。
 *
 * <pre>
 * 0   p0.x p0.y p0.z pad
 * 16  p1.x p1.y p1.z pad
 * 32  p2.x p2.y p2.z pad
 * 48  p3.x p3.y p3.z pad
 * 64  uv0.x uv0.y uv1.x uv1.y
 * 80  uv2.x uv2.y uv3.x uv3.y
 * 96  tint(u32 RGBA8) material(u32: id 低 16 位, bit16 opaque, 其余保留)
 * </pre>
 *
 * 四边形每两个三角形一条记录（0-1-2、2-3-0）；独立三角形复用角 2 作为角 3，
 * 与 BLAS 的固定索引 pattern 对应。
 */
public final class SceneRecordData {
    public static final int STRIDE = 112;
    /** 112 B 恰好是 7 个 16 B 字，shader 可直接按 uvec4 下标读取。 */
    public static final int WORDS = 7;
    public static final int UV_OFFSET = 64;
    public static final int MATERIAL_OFFSET = 100;
    public static final int MATERIAL_ID_MASK = 0xFFFF;
    public static final int MATERIAL_OPAQUE_BIT = 1 << 16;

    private SceneRecordData() {}

    public static int byteSize(TriangleMesh mesh) {
        return Math.multiplyExact(mesh.recordCount(), STRIDE);
    }

    /** 写入完整记录表；调用方负责 16 B 对齐的起始偏移。 */
    public static void write(ByteBuffer target, TriangleMesh mesh) {
        int bytes = byteSize(mesh);
        if (target.capacity() < bytes) throw new IllegalArgumentException("记录缓冲区空间不足");
        target.order(ByteOrder.nativeOrder());
        var positions = mesh.positionsArray();
        var indices = mesh.indicesArray();
        var uvs = mesh.uvsArray();
        var materials = mesh.materialArray();
        var colors = mesh.colorsArray();
        boolean quads = mesh.quads();
        for (int record = 0; record < mesh.recordCount(); record++) {
            int c0, c1, c2, c3;
            if (quads) {
                c0 = record * 4;
                c1 = c0 + 1;
                c2 = c0 + 2;
                c3 = c0 + 3;
            } else {
                c0 = indices[record * 3];
                c1 = indices[record * 3 + 1];
                c2 = indices[record * 3 + 2];
                c3 = c2;
            }
            int base = record * STRIDE;
            position(target, base, positions, c0);
            position(target, base + 16, positions, c1);
            position(target, base + 32, positions, c2);
            position(target, base + 48, positions, c3);
            uv(target, base + 64, uvs, c0);
            uv(target, base + 72, uvs, c1);
            uv(target, base + 80, uvs, c2);
            uv(target, base + 88, uvs, c3);
            int tint = colors.length == 0 ? 0xFFFFFFFF : colors[c0];
            int material = (materials.length == 0 ? 0 : materials[c0] & MATERIAL_ID_MASK)
                    | (mesh.opaque() ? MATERIAL_OPAQUE_BIT : 0);
            target.putInt(base + 96, tint);
            target.putInt(base + MATERIAL_OFFSET, material);
            target.putInt(base + 104, 0);
            target.putInt(base + 108, 0);
        }
        // 绝对写入不移动 position；把末尾作为 flip 后的 limit。
        target.position(bytes);
    }

    private static void position(ByteBuffer target, int offset, float[] positions, int vertex) {
        target.putFloat(offset, positions[vertex * 3]);
        target.putFloat(offset + 4, positions[vertex * 3 + 1]);
        target.putFloat(offset + 8, positions[vertex * 3 + 2]);
        target.putFloat(offset + 12, 0f);
    }

    private static void uv(ByteBuffer target, int offset, float[] uvs, int vertex) {
        boolean present = uvs.length != 0;
        target.putFloat(offset, present ? uvs[vertex * 2] : 0f);
        target.putFloat(offset + 4, present ? uvs[vertex * 2 + 1] : 0f);
    }
}
