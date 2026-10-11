package com.github.slmpc.irisrt.vulkan;

import com.github.slmpc.irisrt.rhi.AccelerationStructure;
import com.github.slmpc.irisrt.rhi.AccelerationStructureDescription;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

/** Native acceleration structure retained by Iris descriptor snapshots until their retirement. */
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
    private int descriptorReferences;
    private boolean destructionQueued;

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

    public long storageBufferHandle() { checkOpen(); return storage.handle; }

    /** Internal descriptor lease; released when Iris retires the descriptor snapshot. */
    public void retainDescriptor() {
        checkOpen();
        descriptorReferences = Math.addExact(descriptorReferences, 1);
    }

    public void releaseDescriptor() {
        owner.checkThread();
        if (descriptorReferences <= 0) throw new IllegalStateException("Unbalanced AS descriptor lease");
        descriptorReferences--;
        queueDestruction();
    }

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
            queueDestruction();
        }
    }

    private void queueDestruction() {
        if (closed && descriptorReferences == 0 && !destructionQueued) {
            destructionQueued = true;
            // Descriptor leases keep the AS alive across cached snapshots and queued GPU work.
            owner.device.createCommandEncoder().queueForDestroy(() -> {
                vkDestroyAccelerationStructureKHR(owner.device.vkDevice(), handle, null);
                storage.destroy();
            });
        }
    }
}
