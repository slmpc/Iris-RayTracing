package com.github.slmpc.luminrt.scene;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SceneUVDataTest {
    private static final float[] XYZ = {0,0,0, 1,0,0, 0,1,0};

    @Test
    void validatesAndOwnsUVsWithoutMakingThemPartOfBlasTopology() {
        float[] uv = {0,0, 1,0, 0,1};
        var mesh = new TriangleMesh(XYZ, new int[]{0,1,2}, uv, true);
        uv[0] = 9;
        mesh.uvs()[1] = 9;
        assertArrayEquals(new float[]{0,0, 1,0, 0,1}, mesh.uvs());
        assertTrue(mesh.sameTopology(new TriangleMesh(XYZ, new int[]{0,1,2}, true)));
        assertThrows(IllegalArgumentException.class, () -> new TriangleMesh(XYZ, new int[]{0,1,2}, new float[]{0,0}, true));
        assertThrows(IllegalArgumentException.class, () -> new TriangleMesh(XYZ, new int[]{0,1,2}, new float[]{Float.NaN,0, 0,0, 0,0}, true));
    }

    @Test
    void packsMultipleInstancesGeometriesAndIndexedTrianglesWithMissingUVFlag() {
        var mesh = new TriangleMesh(XYZ, new int[]{2,0,1, 1,0,2}, new float[]{0,0, 1,0, 0,1}, true);
        var missing = new TriangleMesh(XYZ, new int[]{0,1,2}, true);
        var instances = List.of(List.of(mesh, missing), List.of(mesh));
        var data = ByteBuffer.allocate(SceneUVData.byteSize(instances)).order(ByteOrder.nativeOrder());
        SceneUVData.write(data, instances);
        assertEquals(data.capacity(), data.position());
        assertArrayEquals(new int[]{1,2,3,5}, record(data, 0));
        assertArrayEquals(new int[]{3,2,0,0}, record(data, 1));
        assertArrayEquals(new int[]{5,1,0,0}, record(data, 2));
        assertArrayEquals(new int[]{6,2,1,0}, record(data, 3));
        assertArrayEquals(new int[]{10,1,0,0}, record(data, 4));
        assertArrayEquals(new int[]{12,2,1,0}, record(data, 5));
        assertEquals(0, data.getFloat(6*16));
        assertEquals(1, data.getFloat(6*16+4));
        assertEquals(1, data.getFloat(7*16));
        assertEquals(0, data.getLong(10*16));
        assertThrows(IllegalArgumentException.class, () -> SceneUVData.write(ByteBuffer.allocate(16), instances));
    }

    @Test
    void emptySceneStillPublishesVersionedHeader() {
        var data = ByteBuffer.allocate(SceneUVData.byteSize(List.of())).order(ByteOrder.nativeOrder());
        SceneUVData.write(data, List.of());
        assertEquals(16, data.position());
        assertArrayEquals(new int[]{1,0,0,0}, record(data, 0));
    }

    private static int[] record(ByteBuffer data, int index) {
        return new int[]{data.getInt(index*16), data.getInt(index*16+4), data.getInt(index*16+8), data.getInt(index*16+12)};
    }
}
