package com.github.slmpc.irisrt.mixin;

import com.github.slmpc.irisrt.vulkan.AccelerationStructureSnapshot;
import com.github.slmpc.irisrt.vulkan.VulkanAccelerationStructure;
import net.irisshaders.iris.vulkan.VulkanResourceSets;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = VulkanResourceSets.Snapshot.class, remap = false)
public abstract class VulkanResourceSnapshotMixin implements AccelerationStructureSnapshot {
    @Unique private List<VulkanAccelerationStructure> irisrt$structures = List.of();

    @Override
    public void retainAccelerationStructures(List<VulkanAccelerationStructure> structures) {
        irisrt$structures = List.copyOf(structures);
        irisrt$structures.forEach(VulkanAccelerationStructure::retainDescriptor);
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void irisrt$release(CallbackInfo ci) {
        var structures = irisrt$structures;
        irisrt$structures = List.of();
        structures.forEach(VulkanAccelerationStructure::releaseDescriptor);
    }
}
