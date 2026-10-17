package com.github.slmpc.luminrt.scene;

import java.nio.ByteBuffer;
import java.util.List;

/** 与 TLAS、UV 表共用 primitive 顺序的只读材质表。 */
public final class SceneMaterialData {
    public static final String SHADER_NAME = "luminrt_SceneSurface";
    public static final int VERSION = 1;

    private SceneMaterialData() {}

    public static int byteSize(List<List<TriangleMesh>> instances) {
        int records = Math.addExact(1, instances.size());
        for (var instance : instances) {
            records = Math.addExact(records, instance.size());
            for (var mesh : instance) records = Math.addExact(records, Math.multiplyExact(mesh.triangleCount(), 4));
        }
        return Math.multiplyExact(records, 16);
    }

    public static void write(ByteBuffer target, List<List<TriangleMesh>> instances) {
        write(target, instances, java.util.Collections.nCopies(instances.size(), false));
    }

    public static void write(ByteBuffer target, List<List<TriangleMesh>> instances, List<Boolean> dynamicInstances) {
        if (dynamicInstances.size() != instances.size()) throw new IllegalArgumentException("实例动态标记数量不匹配");
        if (target.remaining() < byteSize(instances)) throw new IllegalArgumentException("材质表空间不足");
        int geometryCount = instances.stream().mapToInt(List::size).reduce(0, Math::addExact);
        int triangleCount = instances.stream().flatMap(List::stream).mapToInt(TriangleMesh::triangleCount).reduce(0, Math::addExact);
        target.putInt(VERSION).putInt(instances.size()).putInt(geometryCount).putInt(triangleCount);
        int geometryBase = 1 + instances.size();
        for (int instanceIndex = 0; instanceIndex < instances.size(); instanceIndex++) {
            var instance = instances.get(instanceIndex);
            target.putInt(geometryBase).putInt(instance.size())
                    .putInt(Boolean.TRUE.equals(dynamicInstances.get(instanceIndex)) ? 1 : 0).putInt(0);
            geometryBase += instance.size();
        }
        int triangleBase = 1 + instances.size() + geometryCount;
        for (int instanceIndex = 0; instanceIndex < instances.size(); instanceIndex++) for (var mesh : instances.get(instanceIndex)) {
            target.putInt(triangleBase).putInt(mesh.triangleCount()).putInt(1).putInt(0);
            triangleBase += mesh.triangleCount() * 4;
        }
        for (int instanceIndex = 0; instanceIndex < instances.size(); instanceIndex++)
            for (var mesh : instances.get(instanceIndex))
                mesh.writeTriangleSurface(target, !Boolean.TRUE.equals(dynamicInstances.get(instanceIndex)));
    }
}
