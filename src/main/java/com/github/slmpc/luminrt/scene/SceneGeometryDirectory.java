package com.github.slmpc.luminrt.scene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * 场景目录（std430 uvec4 数组）：
 *
 * <pre>
 * 0             header: version, instanceCount, geometryCount, anchorGeneration
 * 1             anchor: ivec4(anchor.xyz, 0)
 * 2 .. 2+I      instance: geometryBase, geometryCount, 0, 0
 * 2+I .. 2+I+G  geometry: recordByteOffset, recordCount, flags, 0
 * </pre>
 *
 * flags 的 bit0 为四边形拓扑，bit1 为 opaque。记录本体位于 {@code luminrt_SceneRecords}。
 */
public final class SceneGeometryDirectory {
    public static final String SHADER_NAME = "luminrt_SceneGeometry";
    public static final String RECORDS_SHADER_NAME = "luminrt_SceneRecords";
    public static final int VERSION = 1;
    public static final int HEADER_RECORDS = 2;
    public static final int FLAG_QUADS = 1;
    public static final int FLAG_OPAQUE = 1 << 1;

    public record Instance(int geometryBase, int geometryCount) {}
    public record Geometry(int recordOffset, int recordCount, int flags) {}

    private SceneGeometryDirectory() {}

    public static int byteSize(int instances, int geometries) {
        return Math.multiplyExact(HEADER_RECORDS + instances + geometries, 16);
    }

    public static void write(ByteBuffer target, int anchorX, int anchorY, int anchorZ,
                             long anchorGeneration, List<Instance> instances, List<Geometry> geometries) {
        int bytes = byteSize(instances.size(), geometries.size());
        if (target.capacity() < bytes) throw new IllegalArgumentException("场景目录空间不足");
        target.order(ByteOrder.nativeOrder());
        target.putInt(VERSION);
        target.putInt((int) anchorGeneration);
        target.putInt(instances.size());
        target.putInt(geometries.size());
        target.putInt(anchorX);
        target.putInt(anchorY);
        target.putInt(anchorZ);
        target.putInt(0);
        for (var instance : instances) {
            target.putInt(instance.geometryBase());
            target.putInt(instance.geometryCount());
            target.putInt(0);
            target.putInt(0);
        }
        for (var geometry : geometries) {
            target.putInt(geometry.recordOffset());
            target.putInt(geometry.recordCount());
            target.putInt(geometry.flags());
            target.putInt(0);
        }
    }
}
