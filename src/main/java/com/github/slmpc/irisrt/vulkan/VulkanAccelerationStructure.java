package com.github.slmpc.irisrt.vulkan;

import com.github.slmpc.irisrt.rhi.AccelerationStructure;
import com.github.slmpc.irisrt.rhi.AccelerationStructureDescription;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

/** 由 Iris 描述符快照持有的原生加速结构，直到快照退役才释放相应引用。 */
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

    /** 内部描述符租约；在 Iris 将描述符快照退役时释放。 */
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
            // 描述符租约保证加速结构在快照缓存和已排队的 GPU 工作使用期间保持有效。
            owner.device.createCommandEncoder().queueForDestroy(() -> {
                vkDestroyAccelerationStructureKHR(owner.device.vkDevice(), handle, null);
                storage.destroy();
            });
        }
    }
}
