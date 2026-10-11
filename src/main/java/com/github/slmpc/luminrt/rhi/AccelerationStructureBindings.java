package com.github.slmpc.luminrt.rhi;

import net.irisshaders.iris.samplers.ProgramResources;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 扩展 Iris 的 ProgramResources。每次创建资源快照时，对各提供器求值一次。 */
public interface AccelerationStructureBindings {
    void setAccelerationStructures(String name, Supplier<List<AccelerationStructure>> structures);
    Map<String, List<AccelerationStructure>> resolveAccelerationStructures();

    default void setAccelerationStructure(String name, Supplier<AccelerationStructure> structure) {
        setAccelerationStructures(name, () -> List.of(structure.get()));
    }

    static AccelerationStructureBindings of(ProgramResources resources) {
        return (AccelerationStructureBindings) (Object) resources;
    }
}
