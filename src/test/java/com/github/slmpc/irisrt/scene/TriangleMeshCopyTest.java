package com.github.slmpc.irisrt.scene;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.*;

class TriangleMeshCopyTest {
    @Test
    void copiesPositionAttributeWithActualOffsetAndStrideBeforeSourceRelease() {
        var format = VertexFormat.builder(0).addAttribute("Color", GpuFormat.RGBA8_UNORM)
                .addAttribute("Position", GpuFormat.RGB32_FLOAT).addAttribute("UV0", GpuFormat.RG32_FLOAT).build();
        TriangleMesh copied;
        try (var source = new ByteBufferBuilder(256)) {
            long pointer = source.reserve(format.getVertexSize() * 4);
            for (int i = 0; i < 4; i++) {
                long vertex = pointer + i * format.getVertexSize();
                MemoryUtil.memPutInt(vertex, -1);
                MemoryUtil.memPutFloat(vertex + 4, i);
                MemoryUtil.memPutFloat(vertex + 8, i + 10);
                MemoryUtil.memPutFloat(vertex + 12, i + 20);
            }
            try (var mesh = new MeshData(source.build(), new MeshData.DrawState(format, 4, 6, PrimitiveTopology.QUADS, IndexType.SHORT))) {
                copied = TriangleMesh.copy(mesh, false);
                assertNotNull(copied);
            }
        }
        assertArrayEquals(new float[]{0,10,20, 1,11,21, 2,12,22, 3,13,23}, copied.positions());
        assertArrayEquals(new int[]{0,1,2, 2,3,0}, copied.indices());
        assertFalse(copied.opaque());
    }
}
