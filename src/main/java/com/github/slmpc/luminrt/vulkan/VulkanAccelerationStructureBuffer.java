package com.github.slmpc.luminrt.vulkan;

import com.github.slmpc.luminrt.rhi.AccelerationStructureBuffer;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import net.irisshaders.iris.vulkan.VulkanCommandBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;

import java.nio.ByteBuffer;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK12.*;
import static com.github.slmpc.luminrt.vulkan.VulkanAccelerationStructureFeatures.check;

/** 使用缓冲区设备地址内存标志分配的仅供 GPU 使用的存储、构建输入或临时缓冲区。 */
final class VulkanAccelerationStructureBuffer implements AccelerationStructureBuffer {
    // 当前 Iris Vulkan 用此扩展 usage 位表示存储缓冲区，桥接时检查该位。
    private static final int IRIS_STORAGE_BUFFER_USAGE = 1 << 20;
    final VulkanAccelerationStructureBackend owner;
    final long handle;
    private final long allocation;
    private final long size;
    private final long address;
    private boolean closed;

    VulkanAccelerationStructureBuffer(VulkanAccelerationStructureBackend owner, long size, int usage) {
        if (size <= 0) throw new IllegalArgumentException("Buffer size must be positive");
        this.owner = owner;
        this.size = size;
        try (var stack = MemoryStack.stackPush()) {
            var info = VkBufferCreateInfo.calloc(stack).sType$Default().size(size)
                    .usage(usage | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            var buffer = stack.mallocLong(1);
            var memory = stack.mallocPointer(1);
            check(vmaCreateBuffer(owner.device.vma(), info,
                    VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE),
                    buffer, memory, null), "allocate AS buffer");
            handle = buffer.get(0);
            allocation = memory.get(0);
            try {
                address = vkGetBufferDeviceAddress(owner.device.vkDevice(),
                        VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(handle));
                if (address == 0) throw new IllegalStateException("AS buffer has no device address");
            } catch (RuntimeException | Error e) {
                vmaDestroyBuffer(owner.device.vma(), handle, allocation);
                throw e;
            }
        }
    }

    @Override
    public long size() { return size; }

    @Override
    public long deviceAddress() {
        owner.checkOpen();
        if (closed) throw new IllegalStateException("AS buffer is closed");
        return address;
    }

    @Override
    public boolean isClosed() { return closed; }

    @Override
    public com.mojang.renderpearl.api.buffers.GpuBufferSlice shaderSlice() {
        deviceAddress();
        // Iris 的切片桥接要求 VulkanGpuBuffer；此视图不拥有底层 VMA 分配。
        var view = new VulkanGpuBuffer(handle, IRIS_STORAGE_BUFFER_USAGE, size) {
            @Override public boolean isClosed() { return closed; }
            @Override public void close() { throw new UnsupportedOperationException("Borrowed scene storage view"); }
            @Override public void destroy() { throw new UnsupportedOperationException("Borrowed scene storage view"); }
            @Override public com.mojang.renderpearl.api.buffers.GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
                throw new UnsupportedOperationException("Scene storage is GPU-only");
            }
        };
        return view.slice();
    }

    @Override
    public void upload(long offset, ByteBuffer data) {
        deviceAddress();
        if (!data.isDirect()) throw new IllegalArgumentException("Upload data must be a direct ByteBuffer");
        int bytes = data.remaining();
        if (offset < 0 || (offset & 3) != 0 || (bytes & 3) != 0 || offset > size - bytes) {
            throw new IllegalArgumentException("Invalid upload range/alignment");
        }
        if (bytes == 0) return;
        // 先获取命令缓冲区，在分配暂存内存前拒绝渲染通道内的上传操作。
        var command = VulkanCommandBridge.commandBuffer(owner.device.createCommandEncoder());
        try (var stack = MemoryStack.stackPush();
             GpuBuffer staging = owner.device.createBuffer(() -> "LuminRT AS upload", GpuBuffer.USAGE_COPY_SRC, data)) {
            owner.barrier(command, stack);
            var copy = VkBufferCopy.calloc(1, stack).srcOffset(0).dstOffset(offset).size(bytes);
            vkCmdCopyBuffer(command, ((VulkanGpuBuffer) staging).vkBuffer(), handle, copy);
            owner.barrier(command, stack);
        }
    }

    @Override
    public void close() {
        owner.checkThread();
        if (!closed) {
            closed = true;
            owner.release(this);
            owner.device.createCommandEncoder().queueForDestroy(this::destroy);
        }
    }

    void destroy() { vmaDestroyBuffer(owner.device.vma(), handle, allocation); }
}
