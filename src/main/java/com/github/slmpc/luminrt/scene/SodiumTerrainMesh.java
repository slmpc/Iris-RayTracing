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
        for (int i = 0; i < count; i++) {
            int offset = source.position() + i * stride + position.offset();
            int high = source.getInt(offset), low = source.getInt(offset + 4);
            for (int axis = 0; axis < 3; axis++) {
                int shift = axis * 10;
                int quantized = ((high >>> shift & 1023) << 10) | (low >>> shift & 1023);
                xyz[i * 3 + axis] = quantized * (32.0f / 1048576.0f) - 8.0f;
            }
        }
        return new TriangleMesh(xyz, TriangleMesh.sequentialIndices(PrimitiveTopology.QUADS, count), opaque);
    }
}
