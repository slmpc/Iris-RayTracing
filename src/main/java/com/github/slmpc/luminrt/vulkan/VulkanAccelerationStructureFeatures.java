package com.github.slmpc.luminrt.vulkan;

import com.github.slmpc.luminrt.LuminRT;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayQuery.*;
import static org.lwjgl.vulkan.VK12.*;

/** 可选的设备功能：不支持这些功能的 GPU 保留 Minecraft 原有的设备配置。 */
public final class VulkanAccelerationStructureFeatures {
    private VulkanAccelerationStructureFeatures() { }

    public static void configure(VkPhysicalDevice physicalDevice, VkDeviceCreateInfo info, MemoryStack stack) {
        var count = stack.callocInt(1);
        check(vkEnumerateDeviceExtensionProperties(physicalDevice, (String) null, count, null), "enumerate device extensions");
        Set<String> available = new LinkedHashSet<>();
        // 驱动可能提供数百个扩展，所需数组会超过 LWJGL 默认原生栈的容量。
        try (var extensions = VkExtensionProperties.calloc(count.get(0))) {
            check(vkEnumerateDeviceExtensionProperties(physicalDevice, (String) null, count, extensions), "enumerate device extensions");
            for (int i = 0; i < count.get(0); i++) available.add(extensions.get(i).extensionNameString());
        }
        if (!available.contains(VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME)
                || !available.contains(VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME)) {
            LuminRT.LOGGER.info("Vulkan acceleration structures unavailable: required extensions missing");
            return;
        }

        var supportedAs = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default();
        var query = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default();
        boolean queryExtension = available.contains(VK_KHR_RAY_QUERY_EXTENSION_NAME);
        if (queryExtension) supportedAs.pNext(query.address());
        var supported12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default().pNext(supportedAs.address());
        var supported = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(supported12.address());
        vkGetPhysicalDeviceFeatures2(physicalDevice, supported);
        if (!supported12.bufferDeviceAddress() || !supportedAs.accelerationStructure()) {
            LuminRT.LOGGER.info("Vulkan acceleration structures unavailable: required features missing");
            return;
        }

        // 保留 Minecraft 现有的 pNext 链和所有已启用的功能位。
        long address12 = find(info.pNext(), VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES);
        if (address12 == 0) {
            var enabled12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default().pNext(info.pNext());
            info.pNext(enabled12.address());
            address12 = enabled12.address();
        }
        VkPhysicalDeviceVulkan12Features.create(address12).bufferDeviceAddress(true);
        long addressAs = find(info.pNext(), VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR);
        if (addressAs == 0) {
            var enabledAs = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default().pNext(info.pNext());
            info.pNext(enabledAs.address());
            addressAs = enabledAs.address();
        }
        VkPhysicalDeviceAccelerationStructureFeaturesKHR.create(addressAs).accelerationStructure(true);

        Set<String> enabled = new LinkedHashSet<>();
        PointerBuffer original = info.ppEnabledExtensionNames();
        if (original != null) {
            for (int i = original.position(); i < original.limit(); i++) enabled.add(original.getStringUTF8(i));
        }
        enabled.add(VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME);
        enabled.add(VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);
        if (queryExtension && query.rayQuery()) {
            long addressQuery = find(info.pNext(), VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR);
            if (addressQuery == 0) {
                var enabledQuery = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default().pNext(info.pNext());
                info.pNext(enabledQuery.address());
                addressQuery = enabledQuery.address();
            }
            VkPhysicalDeviceRayQueryFeaturesKHR.create(addressQuery).rayQuery(true);
            enabled.add(VK_KHR_RAY_QUERY_EXTENSION_NAME);
            LuminRT.LOGGER.info("Enabling Vulkan ray query shader support");
        }
        PointerBuffer names = stack.mallocPointer(enabled.size());
        for (String name : enabled) names.put(stack.UTF8(name));
        info.ppEnabledExtensionNames(names.flip());
        LuminRT.LOGGER.info("Enabling Vulkan acceleration structures and buffer device addresses");
    }

    private static long find(long head, int type) {
        for (var node = VkBaseOutStructure.createSafe(head); node != null; node = node.pNext()) {
            if (node.sType() == type) return node.address();
        }
        return 0;
    }

    static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed (VkResult " + result + ")");
    }
}
