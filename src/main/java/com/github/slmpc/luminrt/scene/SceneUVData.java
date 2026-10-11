package com.github.slmpc.luminrt.scene;

import java.nio.ByteBuffer;
import java.util.List;

/** std430 uvec4 表：头部、实例、geometry、每三角形两条 UV 记录，偏移以 16 字节记录计。 */
public final class SceneUVData {
    public static final String SHADER_NAME = "luminrt_SceneUV";
    private SceneUVData() {}

    public static int byteSize(List<List<TriangleMesh>> instances) {
        int records = Math.addExact(1, instances.size());
        for (var instance : instances) {
            records = Math.addExact(records, instance.size());
            for (var mesh : instance) records = Math.addExact(records, Math.multiplyExact(mesh.triangleCount(), 2));
        }
        return Math.multiplyExact(records, 16);
    }

    public static void write(ByteBuffer target, List<List<TriangleMesh>> instances) {
        if (target.remaining() < byteSize(instances)) throw new IllegalArgumentException("UV target buffer is too small");
        int geometryCount = instances.stream().mapToInt(List::size).reduce(0, Math::addExact);
        int triangleCount = instances.stream().flatMap(List::stream).mapToInt(TriangleMesh::triangleCount).reduce(0, Math::addExact);
        target.putInt(1).putInt(instances.size()).putInt(geometryCount).putInt(triangleCount);
        int geometryBase = 1 + instances.size();
        for (var instance : instances) {
            target.putInt(geometryBase).putInt(instance.size()).putLong(0);
            geometryBase += instance.size();
        }
        int triangleBase = 1 + instances.size() + geometryCount;
        for (var instance : instances) for (var mesh : instance) {
            target.putInt(triangleBase).putInt(mesh.triangleCount()).putInt(mesh.hasUVs() ? 1 : 0).putInt(0);
            triangleBase += mesh.triangleCount() * 2;
        }
        for (var instance : instances) for (var mesh : instance) mesh.writeTriangleUVs(target);
    }
}
