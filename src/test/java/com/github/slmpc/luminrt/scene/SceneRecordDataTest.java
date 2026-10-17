package com.github.slmpc.luminrt.scene;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class SceneRecordDataTest {
    @Test
    void separatesPositionBlocksFromShadingBlocksForSnormTriangleInput() {
        var mesh = new TriangleMesh(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0, 2, 0, 0, 3, 0, 0, 2, 1, 0},
                new int[]{0, 1, 2, 3, 4, 5},
                new float[]{0, 0, 1, 0, 0, 1, 0.5f, 0, 1, 0, 0.5f, 1},
                new int[]{7, 7, 7, 9, 9, 9},
                new int[]{0x11223344, 0, 0, 0x55667788, 0, 0},
                new int[0], true);
        assertEquals(2, mesh.recordCount());

        var positions = ByteBuffer.allocate(SceneRecordData.positionByteSize(mesh)).order(ByteOrder.nativeOrder());
        SceneRecordData.writePositions(positions, mesh);
        assertEquals(2 * SceneRecordData.POSITION_STRIDE, SceneRecordData.positionByteSize(mesh));
        // 位置块可直接作为 stride 16 的 BLAS 顶点输入。
        assertEquals(0f, positions.getFloat(0), 0f);
        assertEquals(1f, positions.getFloat(16), 0f);
        assertEquals(0f, positions.getFloat(32), 0f);
        assertEquals(0f, positions.getFloat(48), 0f);
        assertEquals(1f, positions.getFloat(52), 0f);
        assertEquals(3f, positions.getFloat(SceneRecordData.POSITION_STRIDE + 16), 0f);

        var shading = ByteBuffer.allocate(SceneRecordData.shadingByteSize(mesh)).order(ByteOrder.nativeOrder());
        SceneRecordData.writeShading(shading, mesh);
        assertEquals(2 * SceneRecordData.SHADING_STRIDE, SceneRecordData.shadingByteSize(mesh));
        assertEquals(0f, shading.getFloat(0), 0f);
        assertEquals(1f, shading.getFloat(8), 0f);
        assertEquals(0f, shading.getFloat(16), 0f);
        assertEquals(0.5f, shading.getFloat(SceneRecordData.SHADING_STRIDE), 0f);
        assertEquals(0x11223344, shading.getInt(32));
        assertEquals(7 | SceneRecordData.MATERIAL_OPAQUE_BIT, shading.getInt(36));
        assertEquals(9 | SceneRecordData.MATERIAL_OPAQUE_BIT,
                shading.getInt(SceneRecordData.SHADING_STRIDE + 36));
    }

    @Test
    void packsQuadRecordsWithFourIndependentCorners() {
        var mesh = new TriangleMesh(
                new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0},
                new int[]{0, 1, 2, 2, 3, 0},
                new float[]{0, 0, 1, 0, 1, 1, 0, 1},
                new int[]{3, 3, 3, 3}, new int[]{-1, -1, -1, -1}, new int[0], false, true);
        assertEquals(1, mesh.recordCount());

        var positions = ByteBuffer.allocate(SceneRecordData.positionByteSize(mesh)).order(ByteOrder.nativeOrder());
        SceneRecordData.writePositions(positions, mesh);
        assertEquals(1f, positions.getFloat(16), 0f);
        assertEquals(1f, positions.getFloat(32), 0f);
        assertEquals(1f, positions.getFloat(36), 0f);
        assertEquals(0f, positions.getFloat(48), 0f);
        assertEquals(1f, positions.getFloat(52), 0f);

        var shading = ByteBuffer.allocate(SceneRecordData.shadingByteSize(mesh)).order(ByteOrder.nativeOrder());
        SceneRecordData.writeShading(shading, mesh);
        assertEquals(0f, shading.getFloat(0), 0f);
        assertEquals(1f, shading.getFloat(8), 0f);
        assertEquals(1f, shading.getFloat(16), 0f);
        assertEquals(1f, shading.getFloat(20), 0f);
        assertEquals(0f, shading.getFloat(24), 0f);
        assertEquals(3, shading.getInt(36) & SceneRecordData.MATERIAL_ID_MASK);
        assertEquals(0, shading.getInt(36) & SceneRecordData.MATERIAL_OPAQUE_BIT);
    }

    @Test
    void writesSharedIndexPatternForQuadsAndTriangles() {
        var quadIndices = ByteBuffer.allocate(6 * 4).order(ByteOrder.nativeOrder());
        SceneRecordData.writeIndexPattern(quadIndices, true, 1);
        assertEquals(0, quadIndices.getInt(0));
        assertEquals(1, quadIndices.getInt(4));
        assertEquals(2, quadIndices.getInt(8));
        assertEquals(2, quadIndices.getInt(12));
        assertEquals(3, quadIndices.getInt(16));
        assertEquals(0, quadIndices.getInt(20));

        var triangleIndices = ByteBuffer.allocate(3 * 4).order(ByteOrder.nativeOrder());
        SceneRecordData.writeIndexPattern(triangleIndices, false, 1);
        assertEquals(0, triangleIndices.getInt(0));
        assertEquals(1, triangleIndices.getInt(4));
        assertEquals(2, triangleIndices.getInt(8));
    }

    @Test
    void rejectsQuadMeshesThatDoNotUseTheSharedTriangulation() {
        assertThrows(IllegalArgumentException.class, () -> new TriangleMesh(
                new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0},
                new int[]{0, 1, 2, 1, 3, 0},
                new float[0], new int[0], new int[0], new int[0], false, true));
        assertThrows(IllegalArgumentException.class, () -> new TriangleMesh(
                new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0},
                new int[]{0, 1, 2},
                new float[0], new int[0], new int[0], new int[0], false, true));
    }
}
