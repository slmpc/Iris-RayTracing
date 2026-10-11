package com.github.slmpc.irisrt.rhi;

import java.util.List;
import java.util.Objects;

/** 固定的几何布局和数量；更新时仅修改缓冲区内容，不修改此描述。 */
public record AccelerationStructureDescription(AccelerationStructure.Type type,
                                               List<AccelerationGeometry> geometries,
                                               boolean allowUpdate, boolean preferFastTrace) {
    public AccelerationStructureDescription {
        Objects.requireNonNull(type, "type");
        geometries = List.copyOf(geometries);
        if (geometries.isEmpty()) throw new IllegalArgumentException("At least one geometry is required");
        Class<?> geometryType = geometries.getFirst().getClass();
        if (type == AccelerationStructure.Type.TOP_LEVEL) {
            if (geometries.size() != 1 || !(geometries.getFirst() instanceof AccelerationGeometry.Instances)) {
                throw new IllegalArgumentException("TLAS requires exactly one instance geometry");
            }
        } else if (geometries.stream().anyMatch(AccelerationGeometry.Instances.class::isInstance)) {
            throw new IllegalArgumentException("BLAS cannot contain instances");
        } else if (geometries.stream().anyMatch(g -> g.getClass() != geometryType)) {
            throw new IllegalArgumentException("BLAS geometries must have the same type");
        }
    }
}
