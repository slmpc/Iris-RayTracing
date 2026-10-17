package com.github.slmpc.luminrt.vulkan;

import com.github.slmpc.luminrt.rhi.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import net.irisshaders.iris.vulkan.VulkanCommandBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

import static com.github.slmpc.luminrt.vulkan.VulkanAccelerationStructureFeatures.check;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRSynchronization2.*;
import static org.lwjgl.vulkan.VK12.*;

/** 加速结构命令共用 Iris/Minecraft 的图形队列、命令缓冲区和延迟销毁机制。 */
public final class VulkanAccelerationStructureBackend implements AccelerationStructureBackend, AutoCloseable {
    final VulkanDevice device;
    private final boolean supported;
    private final long scratchAlignment;
    private final long maxGeometryCount;
    private final long maxPrimitiveCount;
    private final long maxInstanceCount;
    private final Set<VulkanAccelerationStructure> structures = new HashSet<>();
    private final Set<VulkanAccelerationStructureBuffer> inputs = new HashSet<>();
    private boolean closed;

    public VulkanAccelerationStructureBackend(VulkanDevice device) {
        RenderSystem.assertOnRenderThread();
        this.device = device;
        // 仅在设备支持加速结构和缓冲区设备地址功能时，设备创建钩子才会添加此扩展。
        supported = device.vkDevice().getCapabilities().VK_KHR_acceleration_structure;
        if (supported) {
            try (var stack = MemoryStack.stackPush()) {
                var asProperties = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
                var properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(asProperties.address());
                vkGetPhysicalDeviceProperties2(device.vkDevice().getPhysicalDevice(), properties);
                scratchAlignment = Integer.toUnsignedLong(asProperties.minAccelerationStructureScratchOffsetAlignment());
                maxGeometryCount = asProperties.maxGeometryCount();
                maxPrimitiveCount = asProperties.maxPrimitiveCount();
                maxInstanceCount = asProperties.maxInstanceCount();
            }
        } else {
            scratchAlignment = 1;
            maxGeometryCount = maxPrimitiveCount = maxInstanceCount = 0;
        }
    }

    @Override
    public boolean isSupported() { checkThread(); return supported && !closed; }

    @Override
    public boolean supportsRayQuery() { return isSupported() && device.vkDevice().getCapabilities().VK_KHR_ray_query; }

    @Override
    public AccelerationStructureBuffer createInputBuffer(long size) {
        requireSupport();
        // 通过复制命令上传时，缓冲区大小必须按四字节对齐。
        if (size <= 0 || (size & 3) != 0) throw new IllegalArgumentException("Input size must be positive and aligned to four bytes");
        var buffer = new VulkanAccelerationStructureBuffer(this, size,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        inputs.add(buffer);
        return buffer;
    }

    @Override
    public AccelerationStructure create(AccelerationStructureDescription description) {
        requireSupport();
        validate(description);
        try (var stack = MemoryStack.stackPush(); var geometry = geometries(description)) {
            var info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack).sType$Default()
                    .type(nativeType(description.type())).flags(flags(description))
                    .geometryCount(geometry.remaining()).pGeometries(geometry);
            var counts = description.geometries().stream().mapToInt(AccelerationGeometry::primitiveCount).toArray();
            var sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(device.vkDevice(), VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    info, counts, sizes);
            var storage = new VulkanAccelerationStructureBuffer(this, sizes.accelerationStructureSize(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR);
            long handle = 0;
            try {
                var createInfo = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                        .type(nativeType(description.type())).buffer(storage.handle).offset(0).size(sizes.accelerationStructureSize());
                var result = stack.mallocLong(1);
                check(vkCreateAccelerationStructureKHR(device.vkDevice(), createInfo, null, result), "create acceleration structure");
                handle = result.get(0);
                var structure = new VulkanAccelerationStructure(this, description, storage, handle,
                        sizes.buildScratchSize(), sizes.updateScratchSize());
                structures.add(structure);
                return structure;
            } catch (RuntimeException | Error e) {
                // 此新资源尚未用于任何已提交的命令。
                if (handle != 0) vkDestroyAccelerationStructureKHR(device.vkDevice(), handle, null);
                storage.destroy();
                throw e;
            }
        }
    }

    @Override
    public void build(AccelerationStructure structure) {
        buildBatch(List.of(new AccelerationStructureBackend.Build(structure, false)));
    }

    @Override
    public void update(AccelerationStructure structure) {
        buildBatch(List.of(new AccelerationStructureBackend.Build(structure, true)));
    }

    @Override
    public void buildBatch(List<AccelerationStructureBackend.Build> builds) {
        requireSupport();
        if (builds.isEmpty()) return;
        long scratchTotal = 0;
        for (var build : builds) {
            var structure = structure(build.structure());
            if (build.update() && (!structure.built || !structure.description.allowUpdate())) {
                throw new IllegalStateException("Update requires a prior build with allowUpdate");
            }
            long size = build.update() ? structure.updateScratchSize : structure.buildScratchSize;
            scratchTotal = Math.addExact(scratchTotal, align(size));
        }
        // 在分配临时资源前，拒绝渲染通道内的操作。
        var command = VulkanCommandBridge.commandBuffer(device.createCommandEncoder());
        long allocationSize = Math.addExact(Math.max(1, scratchTotal), scratchAlignment - 1);
        try (var scratch = new VulkanAccelerationStructureBuffer(this, allocationSize,
                     VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
             var stack = MemoryStack.stackPush()) {
            long scratchBase = align(scratch.deviceAddress());
            var infos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(builds.size());
            var geometries = new ArrayList<VkAccelerationStructureGeometryKHR.Buffer>(builds.size());
            var ranges = new ArrayList<VkAccelerationStructureBuildRangeInfoKHR.Buffer>(builds.size());
            var rangePointers = stack.mallocPointer(builds.size());
            try {
                long offset = scratchBase;
                for (int i = 0; i < builds.size(); i++) {
                    var build = builds.get(i);
                    var structure = structure(build.structure());
                    long size = build.update() ? structure.updateScratchSize : structure.buildScratchSize;
                    var geometry = geometries(structure.description);
                    var range = VkAccelerationStructureBuildRangeInfoKHR.calloc(
                            structure.description.geometries().size());
                    for (int j = 0; j < range.capacity(); j++) {
                        range.get(j).primitiveCount(structure.description.geometries().get(j).primitiveCount());
                    }
                    geometries.add(geometry);
                    ranges.add(range);
                    var info = infos.get(i).sType$Default()
                            .type(nativeType(structure.type())).flags(flags(structure.description))
                            .mode(build.update() ? VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR
                                                 : VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                            .srcAccelerationStructure(build.update() ? structure.handle : VK_NULL_HANDLE)
                            .dstAccelerationStructure(structure.handle)
                            .geometryCount(structure.description.geometries().size())
                            .pGeometries(geometry);
                    info.scratchData().deviceAddress(offset);
                    rangePointers.put(i, range.address());
                    offset = Math.addExact(offset, align(size));
                }
                // 收窄到构建输入/结构读写阶段；批量内所有结构共用同一 scratch 分配。
                buildBarrierBefore(command, stack);
                vkCmdBuildAccelerationStructuresKHR(command, infos, rangePointers);
                buildBarrierAfter(command, stack);
                for (var build : builds) ((VulkanAccelerationStructure) build.structure()).built = true;
            } finally {
                geometries.forEach(VkAccelerationStructureGeometryKHR.Buffer::free);
                ranges.forEach(VkAccelerationStructureBuildRangeInfoKHR.Buffer::free);
                infos.free();
            }
        }
    }

    private VulkanAccelerationStructure structure(AccelerationStructure resource) {
        if (!(resource instanceof VulkanAccelerationStructure structure) || structure.owner != this) {
            throw new IllegalArgumentException("Acceleration structure belongs to a different backend");
        }
        structure.checkOpen();
        validate(structure.description);
        return structure;
    }

    private long align(long value) {
        return Math.addExact(value, scratchAlignment - 1) & -scratchAlignment;
    }

    @Override
    public void upload(List<AccelerationStructureBackend.Upload> uploads) {
        requireSupport();
        if (uploads.isEmpty()) return;
        var encoder = device.createCommandEncoder();
        var command = VulkanCommandBridge.commandBuffer(encoder);
        try (var stack = MemoryStack.stackPush()) {
            uploadBarrierBefore(command, stack);
            for (var upload : uploads) {
                if (!(upload.target() instanceof VulkanAccelerationStructureBuffer target) || target.owner != this) {
                    throw new IllegalArgumentException("Upload target belongs to a different Vulkan backend");
                }
                target.deviceAddress();
                target.validateUpload(upload.offset(), upload.data());
                int bytes = upload.data().remaining();
                if (bytes == 0) continue;
                var staging = encoder.transientMemory().uploadStaging(upload.data(), 4L, GpuBuffer.USAGE_COPY_SRC);
                var copy = VkBufferCopy.calloc(1, stack)
                        .srcOffset(staging.offset()).dstOffset(upload.offset()).size(bytes);
                vkCmdCopyBuffer(command, ((VulkanGpuBuffer) staging.buffer()).vkBuffer(), target.handle, copy);
            }
            uploadBarrierAfter(command, stack);
        }
    }

    private void validate(AccelerationStructureDescription description) {
        if (Long.compareUnsigned(description.geometries().size(), maxGeometryCount) > 0) {
            throw new IllegalArgumentException("Geometry count exceeds device limit");
        }
        long primitives = 0;
        for (var geometry : description.geometries()) {
            primitives = Math.addExact(primitives, geometry.primitiveCount());
            switch (geometry) {
                case AccelerationGeometry.Triangles triangles -> {
                    input(triangles.vertices());
                    if (triangles.indices() != null) input(triangles.indices());
                }
                case AccelerationGeometry.Aabbs aabbs -> input(aabbs.buffer());
                case AccelerationGeometry.Instances instances -> input(instances.buffer());
            }
        }
        long limit = description.type() == AccelerationStructure.Type.TOP_LEVEL ? maxInstanceCount : maxPrimitiveCount;
        if (Long.compareUnsigned(primitives, limit) > 0) throw new IllegalArgumentException("Primitive/instance count exceeds device limit");
    }

    private VulkanAccelerationStructureBuffer input(AccelerationStructureBuffer buffer) {
        if (!(buffer instanceof VulkanAccelerationStructureBuffer vkBuffer) || vkBuffer.owner != this || vkBuffer.isClosed()) {
            throw new IllegalArgumentException("Build input is closed or belongs to a different backend");
        }
        return vkBuffer;
    }

    private VkAccelerationStructureGeometryKHR.Buffer geometries(AccelerationStructureDescription description) {
        // 包含多个几何体的 BLAS 数组可能超过 LWJGL 原生栈的容量。
        var result = VkAccelerationStructureGeometryKHR.calloc(description.geometries().size());
        try {
            for (int i = 0; i < result.capacity(); i++) {
                var geometry = description.geometries().get(i);
                var nativeGeometry = result.get(i).sType$Default().flags(geometry.opaque() ? VK_GEOMETRY_OPAQUE_BIT_KHR : 0);
                switch (geometry) {
                    case AccelerationGeometry.Triangles triangles -> {
                        nativeGeometry.geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR);
                        var data = nativeGeometry.geometry().triangles().sType$Default().vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                                .vertexStride(triangles.vertexStride()).maxVertex(triangles.vertexCount() - 1)
                                .indexType(switch (triangles.indexType()) {
                                    case NONE -> VK_INDEX_TYPE_NONE_KHR;
                                    case UINT16 -> VK_INDEX_TYPE_UINT16;
                                    case UINT32 -> VK_INDEX_TYPE_UINT32;
                                });
                        data.vertexData().deviceAddress(Math.addExact(input(triangles.vertices()).deviceAddress(), triangles.vertexOffset()));
                        if (triangles.indices() != null) {
                            data.indexData().deviceAddress(Math.addExact(input(triangles.indices()).deviceAddress(), triangles.indexOffset()));
                        }
                    }
                    case AccelerationGeometry.Aabbs aabbs -> {
                        nativeGeometry.geometryType(VK_GEOMETRY_TYPE_AABBS_KHR);
                        var data = nativeGeometry.geometry().aabbs().sType$Default().stride(aabbs.stride());
                        data.data().deviceAddress(Math.addExact(input(aabbs.buffer()).deviceAddress(), aabbs.offset()));
                    }
                    case AccelerationGeometry.Instances instances -> {
                        nativeGeometry.geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
                        var data = nativeGeometry.geometry().instances().sType$Default().arrayOfPointers(false);
                        data.data().deviceAddress(Math.addExact(input(instances.buffer()).deviceAddress(), instances.offset()));
                    }
                }
            }
            return result;
        } catch (RuntimeException | Error e) {
            result.free();
            throw e;
        }
    }

    void barrier(VkCommandBuffer command, MemoryStack stack, long sourceStage, long sourceAccess,
                 long destinationStage, long destinationAccess) {
        var memory = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(sourceStage).srcAccessMask(sourceAccess)
                .dstStageMask(destinationStage).dstAccessMask(destinationAccess);
        vkCmdPipelineBarrier2KHR(command, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(memory));
    }

    /** 上传前：等待上一帧对目标缓冲的读取和上传完成。 */
    void uploadBarrierBefore(VkCommandBuffer command, MemoryStack stack) {
        barrier(command, stack,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR | VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT_KHR
                        | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR | VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR | VK_ACCESS_2_SHADER_READ_BIT_KHR
                        | VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR, VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR);
    }

    /** 上传后：让后续构建与着色器看到新数据。 */
    void uploadBarrierAfter(VkCommandBuffer command, MemoryStack stack) {
        barrier(command, stack,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR, VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR,
                VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT_KHR
                        | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR | VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_2_SHADER_READ_BIT_KHR | VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR);
    }

    /** 构建前：等待构建输入缓冲的写入和上一帧读取。 */
    void buildBarrierBefore(VkCommandBuffer command, MemoryStack stack) {
        barrier(command, stack,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR | VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT_KHR
                        | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR | VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR | VK_ACCESS_2_SHADER_READ_BIT_KHR
                        | VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR,
                VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                VK_ACCESS_2_SHADER_READ_BIT_KHR | VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR);
    }

    /** 构建后：让 TLAS 构建和后续 ray query 看到新结构。 */
    void buildBarrierAfter(VkCommandBuffer command, MemoryStack stack) {
        barrier(command, stack,
                VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT_KHR | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR
                        | VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_2_SHADER_READ_BIT_KHR);
    }

    private static int nativeType(AccelerationStructure.Type type) {
        return type == AccelerationStructure.Type.BOTTOM_LEVEL
                ? VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR : VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;
    }

    private static int flags(AccelerationStructureDescription description) {
        return (description.allowUpdate() ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR : 0)
                | (description.preferFastTrace() ? VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                                               : VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR);
    }

    private void requireSupport() {
        checkOpen();
        if (!supported) throw new UnsupportedOperationException("Vulkan acceleration structures are unavailable on this device");
    }

    void checkThread() { RenderSystem.assertOnRenderThread(); }

    void checkOpen() {
        checkThread();
        if (closed) throw new IllegalStateException("Acceleration structure backend is closed");
    }

    void release(VulkanAccelerationStructure resource) { structures.remove(resource); }
    void release(VulkanAccelerationStructureBuffer buffer) { inputs.remove(buffer); }

    @Override
    public void close() {
        checkThread();
        if (closed) return;
        for (var resource : Set.copyOf(structures)) resource.close();
        for (var buffer : Set.copyOf(inputs)) buffer.close();
        closed = true;
    }
}
