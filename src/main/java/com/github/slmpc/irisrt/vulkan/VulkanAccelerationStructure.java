package com.github.slmpc.irisrt.vulkan;

import com.github.slmpc.irisrt.rhi.AccelerationStructure;
import com.github.slmpc.irisrt.rhi.AccelerationStructureDescription;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

/** The native handle can be used by a later Vulkan descriptor-set implementation. */
public final class VulkanAccelerationStructure implements AccelerationStructure {
    final VulkanAccelerationStructureBackend owner;
    final AccelerationStructureDescription description;
    final VulkanAccelerationStructureBuffer storage;
    final long handle;
    final long buildScratchSize;
    final long updateScratchSize;
    private final long address;
    private boolean closed;
    boolean built;

    VulkanAccelerationStructure(VulkanAccelerationStructureBackend owner, AccelerationStructureDescription description,
                                VulkanAccelerationStructureBuffer storage, long handle, long buildScratchSize, long updateScratchSize) {
        this.owner = owner;
        this.description = description;
        this.storage = storage;
        this.handle = handle;
        this.buildScratchSize = buildScratchSize;
        this.updateScratchSize = updateScratchSize;
        try (var stack = MemoryStack.stackPush()) {
            address = vkGetAccelerationStructureDeviceAddressKHR(owner.device.vkDevice(),
                    VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(handle));
            if (address == 0) throw new IllegalStateException("Acceleration structure has no device address");
        }
    }

    @Override
    public Type type() { return description.type(); }

    @Override
    public long deviceAddress() { checkOpen(); return address; }

    public long vkHandle() { checkOpen(); return handle; }

    @Override
    public boolean isClosed() { return closed; }

    void checkOpen() {
        owner.checkOpen();
        if (closed) throw new IllegalStateException("Acceleration structure is closed");
    }

    @Override
    public void close() {
        owner.checkThread();
        if (!closed) {
            closed = true;
            owner.release(this);
            // Destroy the AS before its backing buffer, only after outstanding commands finish.
            owner.device.createCommandEncoder().queueForDestroy(() -> {
                vkDestroyAccelerationStructureKHR(owner.device.vkDevice(), handle, null);
                storage.destroy();
            });
        }
    }
}
