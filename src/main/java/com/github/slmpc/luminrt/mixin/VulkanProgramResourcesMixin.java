package com.github.slmpc.luminrt.mixin;

import com.github.slmpc.luminrt.rhi.ResolvedAccelerationStructures;
import com.github.slmpc.luminrt.vulkan.AccelerationStructureDescriptors;
import net.irisshaders.iris.samplers.ProgramResources;
import net.irisshaders.iris.vulkan.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

@Mixin(value = VulkanProgramResources.class, remap = false)
public abstract class VulkanProgramResourcesMixin {
    @Inject(method = "getImageViews", at = @At("RETURN"), cancellable = true)
    private void luminrt$views(ProgramResources.Bindings bindings, CallbackInfoReturnable<Map<String, List<Long>>> cir) {
        var structures = ((ResolvedAccelerationStructures) (Object) bindings).accelerationStructures();
        if (!structures.isEmpty()) cir.setReturnValue(new AccelerationStructureDescriptors.Views(cir.getReturnValue(), structures,
                VulkanCommandBridge.getDevice().vkDevice()));
    }

    @Inject(method = "getResourceAccesses", at = @At("RETURN"))
    private void luminrt$accesses(ProgramResources.Bindings bindings, CallbackInfoReturnable<List<VulkanSynchronization.Access>> cir) {
        var uv = bindings.buffers().get(com.github.slmpc.luminrt.scene.SceneUVData.SHADER_NAME);
        if (uv != null) for (var slice : uv) {
            cir.getReturnValue().add(new VulkanSynchronization.Access(new VulkanSynchronization.Resource(false,
                    ((com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer)slice.buffer()).vkBuffer()),
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT | org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT, false));
        }
        var material = bindings.buffers().get(com.github.slmpc.luminrt.scene.SceneMaterialData.SHADER_NAME);
        if (material != null) for (var slice : material) {
            cir.getReturnValue().add(new VulkanSynchronization.Access(new VulkanSynchronization.Resource(false,
                    ((com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer)slice.buffer()).vkBuffer()),
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT | org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT, false));
        }
        for (var values : ((ResolvedAccelerationStructures) (Object) bindings).accelerationStructures().values()) {
            for (var value : values) {
                var tlas = AccelerationStructureDescriptors.validate(value, VulkanCommandBridge.getDevice().vkDevice());
                cir.getReturnValue().add(new VulkanSynchronization.Access(
                        new VulkanSynchronization.Resource(false, tlas.storageBufferHandle()),
                        org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT | org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR, false));
            }
        }
    }
}
