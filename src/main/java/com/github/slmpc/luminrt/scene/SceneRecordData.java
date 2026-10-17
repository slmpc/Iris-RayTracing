package com.github.slmpc.luminrt.scene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 场景记录布局：位置块与着色块分开连续存放，位置块直接作为 BLAS 顶点输入。
 *
 * <pre>
 * 位置块（64 B = 4 个 uvec4，步长 16）：p0.xyz pad, p1.xyz pad, p2.xyz pad, p3.xyz pad
 * 着色块（48 B = 3 个 uvec4）：uv0/uv1、uv2/uv3、tint+material
 * </pre>
 *
 * 四边形每两个三角形一条记录（0-1-2、2-3-0）；独立三角形复用角 2 作为角 3。
 */
public final class SceneRecordData {
    public static final int POSITION_STRIDE = 64;
    public static final int SHADING_STRIDE = 48;
    public static final int VERTICES_PER_RECORD = 4;
    /** BLAS 顶点格式的步长；位置在每条记录的 0/16/32/48 字节。 */
    public static final int VERTEX_STRIDE = 16;
    public static final int MATERIAL_ID_MASK = 0xFFFF;
    public static final int MATERIAL_OPAQUE_BIT = 1 << 16;

    private SceneRecordData() {}

    public static int shadingByteSize(TriangleMesh mesh) {
        return Math.multiplyExact(mesh.recordCount(), SHADING_STRIDE);
    }

    public static int positionByteSize(TriangleMesh mesh) {
        return Math.multiplyExact(mesh.recordCount(), POSITION_STRIDE);
    }

    /** 写入位置块；调用方负责 16 B 对齐的起始偏移。 */
    public static void writePositions(ByteBuffer target, TriangleMesh mesh) {
        int bytes = positionByteSize(mesh);
        if (target.capacity() < bytes) throw new IllegalArgumentException("位置缓冲区空间不足");
        target.order(ByteOrder.nativeOrder());
        var positions = mesh.positionsArray();
        var indices = mesh.indicesArray();
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
            int base = record * POSITION_STRIDE;
            position(target, base, positions, c0);
            position(target, base + 16, positions, c1);
            position(target, base + 32, positions, c2);
            position(target, base + 48, positions, c3);
        }
        target.position(bytes);
    }

    /** 写入着色块：UV、tint 与材质身份。 */
    public static void writeShading(ByteBuffer target, TriangleMesh mesh) {
        int bytes = shadingByteSize(mesh);
        if (target.capacity() < bytes) throw new IllegalArgumentException("着色缓冲区空间不足");
        target.order(ByteOrder.nativeOrder());
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
            int base = record * SHADING_STRIDE;
            uv(target, base, uvs, c0);
            uv(target, base + 8, uvs, c1);
            uv(target, base + 16, uvs, c2);
            uv(target, base + 24, uvs, c3);
            int tint = colors.length == 0 ? 0xFFFFFFFF : colors[c0];
            int material = (materials.length == 0 ? 0 : materials[c0] & MATERIAL_ID_MASK)
                    | (mesh.opaque() ? MATERIAL_OPAQUE_BIT : 0);
            target.putInt(base + 32, tint);
            target.putInt(base + 36, material);
            target.putInt(base + 40, 0);
            target.putInt(base + 44, 0);
        }
        target.position(bytes);
    }

    /** 写入共享 BLAS 索引 pattern；所有 geometry 的顶点都从自身位置块起点 0 开始。 */
    public static void writeIndexPattern(ByteBuffer target, boolean quads, int records) {
        int perRecord = quads ? 6 : 3;
        int words = Math.multiplyExact(records, perRecord);
        if (target.capacity() < Math.multiplyExact(words, 4)) throw new IllegalArgumentException("索引缓冲区空间不足");
        target.order(ByteOrder.nativeOrder());
        for (int record = 0; record < records; record++) {
            int base = record * VERTICES_PER_RECORD;
            target.putInt(base);
            target.putInt(base + 1);
            target.putInt(base + 2);
            if (quads) {
                target.putInt(base + 2);
                target.putInt(base + 3);
                target.putInt(base);
            }
        }
        target.position(Math.multiplyExact(words, 4));
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
