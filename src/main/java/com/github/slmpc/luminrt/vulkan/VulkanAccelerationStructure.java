package com.github.slmpc.luminrt.vulkan;

import com.github.slmpc.luminrt.rhi.AccelerationStructure;
import com.github.slmpc.luminrt.rhi.AccelerationStructureDescription;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;

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
    private boolean destroyed;
    private final List<Runnable> destroyActions = new java.util.ArrayList<>();
    private Set<VulkanAccelerationStructure> referencedStructures = Set.of();

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
    public void setReferencedStructures(List<AccelerationStructure> structures) {
        checkOpen();
        if (type() != Type.TOP_LEVEL) throw new IllegalStateException("Only TLAS can retain BLAS references");
        var next = new LinkedHashSet<VulkanAccelerationStructure>();
        for (var value : List.copyOf(structures)) {
            if (!(value instanceof VulkanAccelerationStructure blas) || blas.owner != owner
                    || blas.type() != Type.BOTTOM_LEVEL || !blas.built || blas.closed) {
                throw new IllegalArgumentException("Expected a built BLAS from the same backend");
            }
            next.add(blas);
        }
        for (var blas : next) if (!referencedStructures.contains(blas)) blas.retainDescriptor();
        var removed = referencedStructures.stream().filter(blas -> !next.contains(blas)).toList();
        referencedStructures = Set.copyOf(next);
        // 同一 TLAS 原地重建前的命令仍可能访问旧 BLAS，释放必须经过编码器的完成队列。
        if (!removed.isEmpty()) owner.device.createCommandEncoder().queueForDestroy(
                () -> removed.forEach(VulkanAccelerationStructure::releaseDescriptor));
    }

    @Override
    public boolean isClosed() { return closed; }

    @Override
    public void onDestroy(Runnable action) {
        owner.checkThread();
        java.util.Objects.requireNonNull(action);
        if (destroyed) { action.run(); return; }
        // 与 vkDestroy 在同一完成回调中执行，避免提前释放仍被 GPU 读取的记录范围。
        destroyActions.add(action);
    }

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
                referencedStructures.forEach(VulkanAccelerationStructure::releaseDescriptor);
                referencedStructures = Set.of();
                destroyed = true;
                destroyActions.forEach(Runnable::run);
                destroyActions.clear();
            });
        }
    }
}
