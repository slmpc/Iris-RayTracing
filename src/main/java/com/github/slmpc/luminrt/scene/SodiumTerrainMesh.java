package com.github.slmpc.luminrt.scene;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 解码 Sodium 0.9.2 与 Iris XHFP 共用的 20 位区段局部位置。 */
public final class SodiumTerrainMesh {
    private SodiumTerrainMesh() {}

    public static TriangleMesh copy(ByteBuffer vertices, VertexFormat format, boolean opaque) {
        var position = format.getElement("a_Position");
        if (position == null || position.format() != GpuFormat.RG32_UINT)
            throw new IllegalArgumentException("Unsupported Sodium terrain position format");
        int stride = format.getVertexSize();
        if (stride <= 0 || position.offset() < 0 || position.offset() + 8 > stride || vertices.remaining() % stride != 0)
            throw new IllegalArgumentException("Invalid Sodium terrain vertex layout");
        int count = vertices.remaining() / stride;
        if (count % 4 != 0) throw new IllegalArgumentException("Sodium terrain must contain complete quads");
        if (count == 0) return null;
        var source = vertices.duplicate().order(ByteOrder.nativeOrder());
        float[] xyz = new float[Math.multiplyExact(count, 3)];
        int[] colors = new int[count];
        int[] lightData = new int[count];
        int[] materialData = new int[count];
        var color = format.getElement("a_Color");
        if (color != null && color.format() != GpuFormat.RGBA8_UNORM) throw new IllegalArgumentException("Unsupported Sodium terrain color format");
        var light = format.getElement("a_LightAndData");
        if (light != null && light.format() != GpuFormat.RGBA8_UINT) throw new IllegalArgumentException("Unsupported Sodium terrain light format");
        var material = format.getElement("mc_Entity");
        if (material != null && material.format() != GpuFormat.R32_UINT) throw new IllegalArgumentException("Unsupported Sodium terrain material format");
        var uv = format.getElement("a_TexCoord");
        if (uv != null && (uv.format() != GpuFormat.RG16_UINT || uv.offset() < 0 || uv.offset() + 4 > stride))
            throw new IllegalArgumentException("Unsupported Sodium terrain UV format");
        float[] texture = uv == null ? new float[0] : new float[Math.multiplyExact(count, 2)];
        for (int i = 0; i < count; i++) {
            int offset = source.position() + i * stride + position.offset();
            int high = source.getInt(offset), low = source.getInt(offset + 4);
            for (int axis = 0; axis < 3; axis++) {
                int shift = axis * 10;
                int quantized = ((high >>> shift & 1023) << 10) | (low >>> shift & 1023);
                xyz[i * 3 + axis] = quantized * (32.0f / 1048576.0f) - 8.0f;
            }
            colors[i] = color == null ? 0xFFFFFFFF : source.getInt(source.position() + i * stride + color.offset());
            lightData[i] = light == null ? 0 : source.getInt(source.position() + i * stride + light.offset());
            materialData[i] = material == null ? 0 : blockId(source.getInt(
                    source.position() + i * stride + material.offset()));
            if (uv != null) {
                int uvOffset = source.position() + i * stride + uv.offset();
                // 对应 Sodium 的 _vert_tex_diffuse_coord；不包含最高位偏移方向及 mip 收缩修正。
                texture[i * 2] = (source.getShort(uvOffset) & 32767) / 32768.0f;
                texture[i * 2 + 1] = (source.getShort(uvOffset + 2) & 32767) / 32768.0f;
            }
        }
        return new TriangleMesh(xyz, TriangleMesh.sequentialIndices(PrimitiveTopology.QUADS, count), texture,
                materialData, colors, lightData, opaque, true);
    }

    /** Iris XHFP 的 mc_Entity 编码为 {@code (blockId + 1) << 1 | renderTypeBit}。 */
    static int blockId(int raw) {
        return raw == 0 ? 0 : Math.max(0, (raw >>> 1) - 1);
    }
}
