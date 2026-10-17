package com.github.slmpc.luminrt.scene;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** 独立持有位置、三角形索引和可选 UV，不借用 Minecraft 的临时顶点内存。 */
public record TriangleMesh(float[] positions, int[] indices, float[] uvs, int[] materialData, int[] colors,
                           int[] lightData, boolean opaque, boolean quads) {
    public TriangleMesh(float[] positions, int[] indices, boolean opaque) {
        this(positions, indices, new float[0], new int[0], new int[0], new int[0], opaque, false);
    }
    public TriangleMesh(float[] positions, int[] indices, float[] uvs, boolean opaque) {
        this(positions, indices, uvs, new int[0], new int[0], new int[0], opaque, false);
    }
    public TriangleMesh(float[] positions, int[] indices, float[] uvs, int[] materialData, int[] colors,
                        int[] lightData, boolean opaque) {
        this(positions, indices, uvs, materialData, colors, lightData, opaque, false);
    }
    public TriangleMesh {
        positions = positions.clone();
        indices = indices.clone();
        uvs = uvs.clone();
        materialData = materialData.clone();
        colors = colors.clone();
        lightData = lightData.clone();
        if (positions.length == 0 || positions.length % 3 != 0 || indices.length == 0 || indices.length % 3 != 0)
            throw new IllegalArgumentException("Invalid triangle mesh");
        for (float value : positions) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite position");
        for (int index : indices) if (index < 0 || index >= positions.length / 3) throw new IllegalArgumentException("Invalid index");
        if (uvs.length != 0 && uvs.length != positions.length / 3 * 2) throw new IllegalArgumentException("Invalid UV count");
        int vertices = positions.length / 3;
        if (materialData.length != 0 && materialData.length != vertices) throw new IllegalArgumentException("Invalid material count");
        if (colors.length != 0 && colors.length != vertices) throw new IllegalArgumentException("Invalid color count");
        if (lightData.length != 0 && lightData.length != vertices) throw new IllegalArgumentException("Invalid light count");
        for (float value : uvs) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite UV");
        if (quads) {
            // 记录打包与共享索引依赖固定的 4 顶点 / 2 三角形布局。
            if (vertices % 4 != 0 || indices.length % 6 != 0) throw new IllegalArgumentException("Invalid quad mesh");
            for (int quad = 0; quad < vertices / 4; quad++) {
                int first = quad * 4;
                if (indices[quad * 6] != first || indices[quad * 6 + 1] != first + 1
                        || indices[quad * 6 + 2] != first + 2 || indices[quad * 6 + 3] != first + 2
                        || indices[quad * 6 + 4] != first + 3 || indices[quad * 6 + 5] != first) {
                    throw new IllegalArgumentException("Invalid quad indices");
                }
            }
        }
    }

    @Override public float[] positions() { return positions.clone(); }
    @Override public int[] indices() { return indices.clone(); }
    @Override public float[] uvs() { return uvs.clone(); }
    @Override public int[] materialData() { return materialData.clone(); }
    @Override public int[] colors() { return colors.clone(); }
    @Override public int[] lightData() { return lightData.clone(); }

    /** 内部打包读取，返回自持数组而非副本；调用方只读。 */
    float[] positionsArray() { return positions; }
    int[] indicesArray() { return indices; }
    float[] uvsArray() { return uvs; }
    int[] materialArray() { return materialData; }
    int[] colorsArray() { return colors; }

    public int vertexCount() { return positions.length / 3; }
    public int triangleCount() { return indices.length / 3; }

    /** 打包记录数：四边形每两个三角形一条记录，其余每个三角形一条记录。 */
    public int recordCount() { return quads ? triangleCount() / 2 : triangleCount(); }

    public boolean sameTopology(TriangleMesh other) {
        return opaque == other.opaque && quads == other.quads
                && positions.length == other.positions.length && Arrays.equals(indices, other.indices);
    }

    boolean sameContent(TriangleMesh other) {
        return opaque == other.opaque && quads == other.quads
                && Arrays.equals(positions, other.positions)
                && Arrays.equals(indices, other.indices)
                && Arrays.equals(uvs, other.uvs)
                && Arrays.equals(materialData, other.materialData)
                && Arrays.equals(colors, other.colors)
                && Arrays.equals(lightData, other.lightData);
    }

    public static TriangleMesh copy(MeshData mesh, boolean opaque) {
        var state = mesh.drawState();
        return fromBuffered(mesh.vertexBuffer(), state.format(), state.primitiveTopology(),
                state.vertexCount(), opaque);
    }

    /** 从自持顶点字节解码；不借用 Minecraft 缓冲，可在工作线程调用。 */
    public static TriangleMesh fromBuffered(ByteBuffer vertexBuffer, VertexFormat format,
                                            PrimitiveTopology topology, int count, boolean opaque) {
        var position = format.getElement("Position");
        if (position == null || position.format() != GpuFormat.RGB32_FLOAT) return null;
        if (topology != PrimitiveTopology.QUADS && topology != PrimitiveTopology.TRIANGLES
                && topology != PrimitiveTopology.TRIANGLE_STRIP && topology != PrimitiveTopology.TRIANGLE_FAN) return null;
        if (count < 3) return null;
        var source = vertexBuffer.duplicate().order(ByteOrder.nativeOrder());
        float[] xyz = new float[Math.multiplyExact(count, 3)];
        int[] materialData = new int[count];
        int[] colors = new int[count];
        int[] lightData = new int[count];
        var uv = format.getElement("UV0");
        if (uv != null && uv.format() != GpuFormat.RG32_FLOAT) throw new IllegalArgumentException("Unsupported UV0 format");
        float[] texture = uv == null ? new float[0] : new float[Math.multiplyExact(count, 2)];
        var color = format.getElement("Color");
        var light = format.getElement("UV2");
        var entity = format.getElement("mc_Entity");
        for (int i = 0; i < count; i++) {
            int offset = source.position() + i * format.getVertexSize() + position.offset();
            for (int axis = 0; axis < 3; axis++) xyz[i * 3 + axis] = source.getFloat(offset + axis * 4);
            if (uv != null) {
                int uvOffset = source.position() + i * format.getVertexSize() + uv.offset();
                texture[i * 2] = source.getFloat(uvOffset);
                texture[i * 2 + 1] = source.getFloat(uvOffset + 4);
            }
            colors[i] = color == null ? 0xFFFFFFFF : source.getInt(source.position() + i * format.getVertexSize() + color.offset());
            lightData[i] = light == null ? 0 : Short.toUnsignedInt(source.getShort(source.position() + i * format.getVertexSize() + light.offset()))
                    | Short.toUnsignedInt(source.getShort(source.position() + i * format.getVertexSize() + light.offset() + 2)) << 16;
            materialData[i] = entity == null ? 0 : Math.max(0, source.getShort(
                    source.position() + i * format.getVertexSize() + entity.offset()));
        }
        // 四边形排序仅改变绘制顺序；AS 保持固定的 0-1-2、2-3-0 三角化。
        int[] indices = sequentialIndices(topology, count);
        if (indices.length == 0) return null;
        return new TriangleMesh(xyz, indices, texture, materialData, colors, lightData, opaque,
                topology == PrimitiveTopology.QUADS);
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
