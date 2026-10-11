package com.github.slmpc.irisrt.mixin;

import com.github.slmpc.irisrt.vulkan.VulkanAccelerationStructureFeatures;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import static org.lwjgl.util.vma.Vma.*;

@Mixin(value = VulkanBackend.class, remap = false)
public abstract class VulkanBackendMixin {
    @ModifyArgs(
        method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I")
    )
    private static void irisrt$enableAccelerationStructures(Args args) {
        VulkanAccelerationStructureFeatures.configure((VkPhysicalDevice) args.get(0),
                (VkDeviceCreateInfo) args.get(1), MemoryStack.stackGet());
    }

    @Redirect(method = "createVma", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/util/vma/Vma;vmaCreateAllocator(Lorg/lwjgl/util/vma/VmaAllocatorCreateInfo;Lorg/lwjgl/PointerBuffer;)I"))
    private static int irisrt$enableAddressAllocations(VmaAllocatorCreateInfo info, PointerBuffer result, VkDevice device) {
        if (device.getCapabilities().VK_KHR_acceleration_structure) {
            info.flags(info.flags() | VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT);
        }
        return vmaCreateAllocator(info, result);
    }
}
