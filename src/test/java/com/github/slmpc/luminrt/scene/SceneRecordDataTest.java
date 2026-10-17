package com.github.slmpc.luminrt.scene;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class SceneRecordDataTest {
    @Test
    void packsTriangleRecordsAndDuplicatesLastCorner() {
        var mesh = new TriangleMesh(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0, 2, 0, 0, 3, 0, 0, 2, 1, 0},
                new int[]{0, 1, 2, 3, 4, 5},
                new float[]{0, 0, 1, 0, 0, 1, 0.5f, 0, 1, 0, 0.5f, 1},
                new int[]{7, 7, 7, 9, 9, 9},
                new int[]{0x11223344, 0, 0, 0x55667788, 0, 0},
                new int[0], true);
        var data = ByteBuffer.allocate(SceneRecordData.byteSize(mesh)).order(ByteOrder.nativeOrder());
        SceneRecordData.write(data, mesh);

        assertEquals(2, mesh.recordCount());
        assertEquals(2 * SceneRecordData.STRIDE, SceneRecordData.byteSize(mesh));
        // 第一条记录：p0 为原点、p1=(1,0,0)、p3 复用 p2。
        assertEquals(0f, data.getFloat(0), 0f);
        assertEquals(1f, data.getFloat(16), 0f);
        assertEquals(0f, data.getFloat(48), 0f);
        assertEquals(1f, data.getFloat(52), 0f);
        assertEquals(0f, data.getFloat(64), 0f);
        assertEquals(1f, data.getFloat(72), 0f);
        assertEquals(0f, data.getFloat(80), 0f);
        assertEquals(0f, data.getFloat(88), 0f);
        assertEquals(0x11223344, data.getInt(96));
        assertEquals(7 | SceneRecordData.MATERIAL_OPAQUE_BIT, data.getInt(SceneRecordData.MATERIAL_OFFSET));
        // 第二条记录从 112 B 开始，材质身份来自其首顶点。
        assertEquals(3f, data.getFloat(SceneRecordData.STRIDE + 16), 0f);
        assertEquals(9 | SceneRecordData.MATERIAL_OPAQUE_BIT,
                data.getInt(SceneRecordData.STRIDE + SceneRecordData.MATERIAL_OFFSET));
    }

    @Test
    void packsQuadRecordsWithFourIndependentCorners() {
        var mesh = new TriangleMesh(
                new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0},
                new int[]{0, 1, 2, 2, 3, 0},
                new float[]{0, 0, 1, 0, 1, 1, 0, 1},
                new int[]{3, 3, 3, 3}, new int[]{-1, -1, -1, -1}, new int[0], false, true);
        var data = ByteBuffer.allocate(SceneRecordData.byteSize(mesh)).order(ByteOrder.nativeOrder());
        SceneRecordData.write(data, mesh);

        assertEquals(1, mesh.recordCount());
        assertEquals(SceneRecordData.STRIDE, SceneRecordData.byteSize(mesh));
        assertEquals(1f, data.getFloat(16), 0f);
        assertEquals(0f, data.getFloat(48), 0f);
        assertEquals(1f, data.getFloat(52), 0f);
        assertEquals(0f, data.getFloat(64), 0f);
        assertEquals(1f, data.getFloat(72), 0f);
        assertEquals(1f, data.getFloat(80), 0f);
        assertEquals(1f, data.getFloat(84), 0f);
        assertEquals(0f, data.getFloat(88), 0f);
        assertEquals(3, data.getInt(SceneRecordData.MATERIAL_OFFSET) & SceneRecordData.MATERIAL_ID_MASK);
        assertEquals(0, data.getInt(SceneRecordData.MATERIAL_OFFSET) & SceneRecordData.MATERIAL_OPAQUE_BIT);
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
