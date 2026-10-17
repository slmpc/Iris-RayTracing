package com.github.slmpc.luminrt.scene;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class SodiumTerrainMeshTest {
    @Test
    void decodesRealSodiumEncoderAndOwnsSnapshotAfterSourceFree() {
        var encoder = new CompactChunkVertex();
        var vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
        float[] expected = {-2, 0, 16, 1, 0, 16, 1, 3.5f, 16, -2, 3.5f, 16};
        for (int i = 0; i < 4; i++) {
            vertices[i].x = expected[i * 3];
            vertices[i].y = expected[i * 3 + 1];
            vertices[i].z = expected[i * 3 + 2];
            vertices[i].ao = 1;
            vertices[i].u = (i == 0 || i == 3) ? .25f : .75f;
            vertices[i].v = i < 2 ? .125f : .875f;
        }
        var source = MemoryUtil.memAlloc(4 * CompactChunkVertex.STRIDE);
        TriangleMesh mesh;
        try {
            encoder.getEncoder().write(MemoryUtil.memAddress(source), 0, vertices, 255);
            mesh = SodiumTerrainMesh.copy(source, encoder.getVertexFormat(), true);
        } finally { MemoryUtil.memFree(source); }
        assertArrayEquals(expected, mesh.positions());
        assertArrayEquals(new int[]{0, 1, 2, 2, 3, 0}, mesh.indices());
        assertTrue(mesh.opaque());
        float bias = 1f / 32768;
        assertArrayEquals(new float[]{.25f+bias,.125f+bias, .75f-bias,.125f+bias,
                .75f-bias,.875f-bias, .25f+bias,.875f-bias}, mesh.uvs());
    }

    @Test
    void respectsExtendedStrideOffsetsAndBufferRange() {
        var format = VertexFormat.builder(0).addAttribute("padding", GpuFormat.R32_UINT)
                .addAttribute("a_Position", GpuFormat.RG32_UINT).addAttribute("extra", GpuFormat.RGBA32_FLOAT).build();
        int stride = format.getVertexSize();
        var source = ByteBuffer.allocate(8 + stride * 8).order(ByteOrder.nativeOrder());
        source.position(8).limit(8 + stride * 4);
        for (int i = 0; i < 4; i++) {
            // 量化坐标 0x40000 对应 0；高两位无关数据不能污染位置。
            source.putInt(8 + i * stride + 4, 0xc0000000 | 256 | 256 << 10 | 256 << 20);
            source.putInt(8 + i * stride + 8, 0xc0000000);
        }
        var mesh = SodiumTerrainMesh.copy(source, format, false);
        assertEquals(4, mesh.vertexCount());
        assertArrayEquals(new float[12], mesh.positions());
        assertFalse(mesh.opaque());
        assertEquals(8, source.position());
        assertEquals(8 + stride * 4, source.limit());
        source.putInt(12, 0);
        assertArrayEquals(new float[12], mesh.positions());
    }

    @Test
    void rejectsTruncatedQuadsAndUnknownFormats() {
        var format = new CompactChunkVertex().getVertexFormat();
        assertNull(SodiumTerrainMesh.copy(ByteBuffer.allocate(0), format, true));
        assertThrows(IllegalArgumentException.class, () -> SodiumTerrainMesh.copy(ByteBuffer.allocate(19), format, true));
        assertThrows(IllegalArgumentException.class, () -> SodiumTerrainMesh.copy(ByteBuffer.allocate(60), format, true));
        var unknown = VertexFormat.builder(0).addAttribute("a_Position", GpuFormat.RGB32_FLOAT).build();
        assertThrows(IllegalArgumentException.class, () -> SodiumTerrainMesh.copy(ByteBuffer.allocate(48), unknown, true));
    }

    @Test
    void decodesIrisEntityEncodingToBlockId() {
        var format = VertexFormat.builder(0)
                .addAttribute("a_Position", GpuFormat.RG32_UINT)
                .addAttribute("mc_Entity", GpuFormat.R32_UINT)
                .addAttribute("a_TexCoord", GpuFormat.RG16_UINT).build();
        int stride = format.getVertexSize();
        var source = ByteBuffer.allocate(stride * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 4; i++) {
            int base = i * stride;
            // 量化坐标 0x40000 对应世界 0，避免位置校验干扰材质断言。
            source.putInt(base, 256);
            source.putInt(base + 4, 0);
            source.putInt(base + 8, i == 0 ? 13 : 2);
            source.putShort(base + 12, (short)0);
            source.putShort(base + 14, (short)0);
        }
        var mesh = SodiumTerrainMesh.copy(source, format, true);
        // (13 >> 1) - 1 = 5，(2 >> 1) - 1 = 0。
        assertArrayEquals(new int[]{5, 0, 0, 0}, mesh.materialData());
    }
}
