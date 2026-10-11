package com.github.slmpc.irisrt.rhi;

import net.irisshaders.iris.samplers.ProgramResources;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Added to Iris ProgramResources. Suppliers are resolved once for each resource snapshot. */
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
