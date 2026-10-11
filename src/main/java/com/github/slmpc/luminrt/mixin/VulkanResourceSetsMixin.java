package com.github.slmpc.luminrt.mixin;

import com.github.slmpc.luminrt.vulkan.AccelerationStructureSnapshot;
import com.github.slmpc.luminrt.vulkan.AccelerationStructureDescriptors;
import com.github.slmpc.luminrt.vulkan.VulkanAccelerationStructure;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout;
import net.irisshaders.iris.vulkan.VulkanResourceSets;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

import java.util.*;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;

@Mixin(value = VulkanResourceSets.class, remap = false)
public abstract class VulkanResourceSetsMixin {
    @Shadow @Final private VkDevice device;
    @Shadow @Final private Map<String, ShaderResourceLayout.Descriptor> images;

    @Inject(method = "descriptorType", at = @At("HEAD"), cancellable = true)
    private static void luminrt$descriptorType(ShaderResourceLayout.Descriptor descriptor, CallbackInfoReturnable<Integer> cir) {
        if (AccelerationStructureDescriptors.isAccelerationStructure(descriptor)) {
            cir.setReturnValue(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
        }
    }

    @Inject(method = "<init>(Lorg/lwjgl/vulkan/VkDevice;Lnet/irisshaders/iris/pipeline/transform/ShaderResourceLayout;II)V", at = @At("HEAD"))
    private static void luminrt$checkFeatures(VkDevice device, ShaderResourceLayout layout, int capacity, int set, CallbackInfo ci) {
        if (layout.descriptors().stream().anyMatch(AccelerationStructureDescriptors::isAccelerationStructure)
                && !device.getCapabilities().VK_KHR_ray_query) {
            throw new UnsupportedOperationException("Acceleration structure shader bindings require Vulkan ray query support");
        }
    }

    @Inject(method = "snapshot(Ljava/util/Map;Ljava/util/Map;)Lnet/irisshaders/iris/vulkan/VulkanResourceSets$Snapshot;", at = @At("HEAD"))
    private void luminrt$validate(Map<String, List<VulkanResourceSets.BufferSlice>> buffers, Map<String, List<Long>> views,
                                 CallbackInfoReturnable<VulkanResourceSets.Snapshot> cir) {
        for (var descriptor : images.values()) {
            if (AccelerationStructureDescriptors.isAccelerationStructure(descriptor)) {
                if (!(views instanceof AccelerationStructureDescriptors.Views resources)) {
                    throw new IllegalArgumentException("AS snapshots require resolved TLAS references, not raw handles");
                }
                var values = resources.structures.get(descriptor.name());
                if (values == null || values.size() != descriptor.count()) throw new IllegalArgumentException("Missing/wrong TLAS binding: " + descriptor.name());
                var handles = values.stream().map(value -> AccelerationStructureDescriptors.validate(value, device).vkHandle()).toList();
                if (!handles.equals(views.get(descriptor.name()))) throw new IllegalArgumentException("TLAS handles do not match resources");
            }
        }
    }

    @Inject(method = "snapshot(Ljava/util/Map;Ljava/util/Map;)Lnet/irisshaders/iris/vulkan/VulkanResourceSets$Snapshot;", at = @At("RETURN"))
    private void luminrt$retain(Map<String, List<VulkanResourceSets.BufferSlice>> buffers, Map<String, List<Long>> views,
                               CallbackInfoReturnable<VulkanResourceSets.Snapshot> cir) {
        if (views instanceof AccelerationStructureDescriptors.Views resources) {
            List<VulkanAccelerationStructure> structures = new ArrayList<>();
            for (var descriptor : images.values()) {
                if (AccelerationStructureDescriptors.isAccelerationStructure(descriptor)) {
                    resources.structures.get(descriptor.name()).forEach(value -> structures.add((VulkanAccelerationStructure) value));
                }
            }
            ((AccelerationStructureSnapshot) (Object) cir.getReturnValue()).retainAccelerationStructures(structures);
        }
    }

    @Redirect(method = "snapshot(Ljava/util/Map;Ljava/util/Map;)Lnet/irisshaders/iris/vulkan/VulkanResourceSets$Snapshot;",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK10;vkUpdateDescriptorSets(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkWriteDescriptorSet$Buffer;Lorg/lwjgl/vulkan/VkCopyDescriptorSet$Buffer;)V"))
    private void luminrt$writeDescriptors(VkDevice device, VkWriteDescriptorSet.Buffer writes, VkCopyDescriptorSet.Buffer copies) {
        try (var stack = MemoryStack.stackPush()) {
            for (var descriptor : images.values()) {
                if (!AccelerationStructureDescriptors.isAccelerationStructure(descriptor)) continue;
                for (int i = writes.position(); i < writes.limit(); i++) {
                    var write = writes.get(i);
                    if (write.dstBinding() != descriptor.binding()) continue;
                    var handles = stack.mallocLong(descriptor.count());
                    var original = Objects.requireNonNull(write.pImageInfo());
                    for (int j = 0; j < descriptor.count(); j++) handles.put(original.get(j).imageView());
                    handles.flip();
                    var as = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default()
                            .pAccelerationStructures(handles);
                    write.descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).pImageInfo(null).pNext(as.address());
                }
            }
            VK10.vkUpdateDescriptorSets(device, writes, copies);
        }
    }
}
