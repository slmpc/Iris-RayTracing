package com.github.slmpc.luminrt.vulkan;

import com.github.slmpc.luminrt.rhi.AccelerationStructure;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout;
import org.lwjgl.vulkan.VkDevice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 使用类型标记扩展描述符语义，同时保持 Iris 现有枚举的二进制兼容性。 */
public final class AccelerationStructureDescriptors {
    public static final String TYPE = "luminrt_accelerationStructureEXT";

    private AccelerationStructureDescriptors() { }

    public static boolean isAccelerationStructure(ShaderResourceLayout.Descriptor descriptor) {
        return descriptor.kind() == ShaderResourceLayout.Kind.IMAGE && TYPE.equals(descriptor.type());
    }

    public static VulkanAccelerationStructure validate(AccelerationStructure resource, VkDevice device) {
        if (!(resource instanceof VulkanAccelerationStructure structure)) {
            throw new IllegalArgumentException("Expected a Vulkan acceleration structure");
        }
        structure.checkOpen();
        if (structure.owner.device.vkDevice() != device || structure.type() != AccelerationStructure.Type.TOP_LEVEL) {
            throw new IllegalArgumentException("Shader binding requires a TLAS from the pipeline device");
        }
        if (!structure.built) throw new IllegalStateException("Shader TLAS must be built before binding");
        return structure;
    }

    /** 在传递 Iris 快照 API 所需的原生句柄时，同时携带资源的强引用。 */
    public static final class Views extends LinkedHashMap<String, List<Long>> {
        public final Map<String, List<AccelerationStructure>> structures;

        public Views(Map<String, List<Long>> images, Map<String, List<AccelerationStructure>> structures, VkDevice device) {
            super(images);
            this.structures = Map.copyOf(structures);
            structures.forEach((name, values) -> {
                var handles = values.stream().map(value -> validate(value, device).vkHandle()).toList();
                if (putIfAbsent(name, handles) != null) throw new IllegalArgumentException("Conflicting resource name: " + name);
            });
        }
    }
}
