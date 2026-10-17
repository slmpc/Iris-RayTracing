package com.github.slmpc.luminrt.scene;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SceneMaterialDataTest {
    @Test
    void writesStablePrimitiveRecords() {
        var mesh = new TriangleMesh(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
                new int[]{0, 1, 2}, new float[0], new int[]{6, 6, 6}, new int[0], new int[0], true);
        var instances = List.of(List.of(mesh));
        var data = ByteBuffer.allocate(SceneMaterialData.byteSize(instances)).order(ByteOrder.nativeOrder());
        SceneMaterialData.write(data, instances);
        assertEquals(1, data.getInt(0));
        int geometry = data.getInt(16);
        int triangle = data.getInt(geometry * 16);
        assertEquals(6, data.getInt(triangle * 16));
        assertEquals(3, data.getInt(triangle * 16 + 4));
        assertEquals(1.0f, data.getFloat((triangle + 2) * 16));
    }

    @Test
    void rejectsShortBuffer() {
        assertThrows(IllegalArgumentException.class, () -> SceneMaterialData.write(ByteBuffer.allocate(8), List.of()));
    }

    @Test
    void marksStaticAndDynamicTrianglesForRadianceCachePolicy() {
        var mesh = new TriangleMesh(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
                new int[]{0, 1, 2}, new float[0], new int[]{6, 6, 6}, new int[0], new int[0], true);
        var instances = List.of(List.of(mesh), List.of(mesh));
        var data = ByteBuffer.allocate(SceneMaterialData.byteSize(instances)).order(ByteOrder.nativeOrder());
        SceneMaterialData.write(data, instances, List.of(false, true));
        int firstTriangle = 1 + instances.size() + instances.stream().mapToInt(List::size).sum();
        int secondTriangle = firstTriangle + mesh.triangleCount() * 4;
        assertEquals(3, data.getInt(firstTriangle * 16 + 4));
        assertEquals(1, data.getInt(secondTriangle * 16 + 4));
    }
}
