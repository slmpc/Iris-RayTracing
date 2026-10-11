package com.github.slmpc.luminrt.mixin;

import com.github.slmpc.luminrt.vulkan.AccelerationStructureSnapshot;
import com.github.slmpc.luminrt.vulkan.VulkanAccelerationStructure;
import net.irisshaders.iris.vulkan.VulkanResourceSets;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = VulkanResourceSets.Snapshot.class, remap = false)
public abstract class VulkanResourceSnapshotMixin implements AccelerationStructureSnapshot {
    @Unique private List<VulkanAccelerationStructure> luminrt$structures = List.of();

    @Override
    public void retainAccelerationStructures(List<VulkanAccelerationStructure> structures) {
        luminrt$structures = List.copyOf(structures);
        luminrt$structures.forEach(VulkanAccelerationStructure::retainDescriptor);
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void luminrt$release(CallbackInfo ci) {
        var structures = luminrt$structures;
        luminrt$structures = List.of();
        structures.forEach(VulkanAccelerationStructure::releaseDescriptor);
    }
}
