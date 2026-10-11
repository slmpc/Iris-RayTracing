package com.github.slmpc.luminrt.scene;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** 独立持有位置、三角形索引和可选 UV，不借用 Minecraft 的临时顶点内存。 */
public record TriangleMesh(float[] positions, int[] indices, float[] uvs, boolean opaque) {
    public TriangleMesh(float[] positions, int[] indices, boolean opaque) { this(positions, indices, new float[0], opaque); }
    public TriangleMesh {
        positions = positions.clone();
        indices = indices.clone();
        uvs = uvs.clone();
        if (positions.length == 0 || positions.length % 3 != 0 || indices.length == 0 || indices.length % 3 != 0)
            throw new IllegalArgumentException("Invalid triangle mesh");
        for (float value : positions) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite position");
        for (int index : indices) if (index < 0 || index >= positions.length / 3) throw new IllegalArgumentException("Invalid index");
        if (uvs.length != 0 && uvs.length != positions.length / 3 * 2) throw new IllegalArgumentException("Invalid UV count");
        for (float value : uvs) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite UV");
    }

    @Override public float[] positions() { return positions.clone(); }
    @Override public int[] indices() { return indices.clone(); }
    @Override public float[] uvs() { return uvs.clone(); }
    public boolean hasUVs() { return uvs.length != 0; }

    public int vertexCount() { return positions.length / 3; }
    public int triangleCount() { return indices.length / 3; }

    public boolean sameTopology(TriangleMesh other) {
        return opaque == other.opaque && positions.length == other.positions.length && Arrays.equals(indices, other.indices);
    }

    void writePositions(ByteBuffer target) { target.asFloatBuffer().put(positions); }
    void writeIndices(ByteBuffer target) { target.asIntBuffer().put(indices); }

    boolean sameUVs(TriangleMesh other) {
        return Arrays.equals(indices, other.indices) && Arrays.equals(uvs, other.uvs);
    }

    void writeTriangleUVs(ByteBuffer target) {
        for (int triangle = 0; triangle < triangleCount(); triangle++) {
            for (int vertex = 0; vertex < 3; vertex++) {
                int index = indices[triangle * 3 + vertex] * 2;
                target.putFloat(hasUVs() ? uvs[index] : 0).putFloat(hasUVs() ? uvs[index + 1] : 0);
            }
            target.putLong(0);
        }
    }

    public static TriangleMesh copy(MeshData mesh, boolean opaque) {
        var state = mesh.drawState();
        var position = state.format().getElement("Position");
        if (position == null || position.format() != GpuFormat.RGB32_FLOAT) return null;
        var topology = state.primitiveTopology();
        if (topology != PrimitiveTopology.QUADS && topology != PrimitiveTopology.TRIANGLES
                && topology != PrimitiveTopology.TRIANGLE_STRIP && topology != PrimitiveTopology.TRIANGLE_FAN) return null;
        int count = state.vertexCount();
        if (count < 3) return null;
        var source = mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
        float[] xyz = new float[Math.multiplyExact(count, 3)];
        var uv = state.format().getElement("UV0");
        if (uv != null && uv.format() != GpuFormat.RG32_FLOAT) throw new IllegalArgumentException("Unsupported UV0 format");
        float[] texture = uv == null ? new float[0] : new float[Math.multiplyExact(count, 2)];
        for (int i = 0; i < count; i++) {
            int offset = source.position() + i * state.format().getVertexSize() + position.offset();
            for (int axis = 0; axis < 3; axis++) xyz[i * 3 + axis] = source.getFloat(offset + axis * 4);
            if (uv != null) {
                int uvOffset = source.position() + i * state.format().getVertexSize() + uv.offset();
                texture[i * 2] = source.getFloat(uvOffset);
                texture[i * 2 + 1] = source.getFloat(uvOffset + 4);
            }
        }
        // 四边形排序仅改变绘制顺序；AS 保持固定的 0-1-2、2-3-0 三角化。
        int[] indices = sequentialIndices(topology, count);
        if (indices.length == 0) return null;
        return new TriangleMesh(xyz, indices, texture, opaque);
    }

    public static int[] sequentialIndices(PrimitiveTopology topology, int count) {
        if (count < 0) throw new IllegalArgumentException("Negative vertex count");
        int triangles = switch (topology) {
            case QUADS -> count / 4 * 2;
            case TRIANGLES -> count / 3;
            case TRIANGLE_STRIP, TRIANGLE_FAN -> Math.max(0, count - 2);
            default -> 0;
        };
        int[] result = new int[Math.multiplyExact(triangles, 3)];
        for (int i = 0; i < triangles; i++) {
            int offset = i * 3;
            switch (topology) {
                case QUADS -> {
                    int first = i / 2 * 4;
                    result[offset] = first + (i % 2 == 0 ? 0 : 2);
                    result[offset + 1] = first + (i % 2 == 0 ? 1 : 3);
                    result[offset + 2] = first + (i % 2 == 0 ? 2 : 0);
                }
                case TRIANGLES -> { result[offset] = offset; result[offset + 1] = offset + 1; result[offset + 2] = offset + 2; }
                case TRIANGLE_STRIP -> { result[offset] = i + (i & 1); result[offset + 1] = i + 1 - (i & 1); result[offset + 2] = i + 2; }
                case TRIANGLE_FAN -> { result[offset] = 0; result[offset + 1] = i + 1; result[offset + 2] = i + 2; }
                default -> throw new IllegalArgumentException("Unsupported topology");
            }
        }
        return result;
    }
}
