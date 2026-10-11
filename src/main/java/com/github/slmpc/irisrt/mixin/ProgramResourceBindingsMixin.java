package com.github.slmpc.irisrt.mixin;

import com.github.slmpc.irisrt.rhi.*;
import net.irisshaders.iris.samplers.ProgramResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;
import java.util.Map;

@Mixin(value = ProgramResources.Bindings.class, remap = false)
public abstract class ProgramResourceBindingsMixin implements ResolvedAccelerationStructures {
    @Unique private Map<String, List<AccelerationStructure>> irisrt$structures = Map.of();

    @Override public Map<String, List<AccelerationStructure>> accelerationStructures() { return irisrt$structures; }
    @Override public void setAccelerationStructures(Map<String, List<AccelerationStructure>> structures) {
        irisrt$structures = Map.copyOf(structures);
    }
}
