package com.github.slmpc.irisrt.vulkan;

import com.github.slmpc.irisrt.rhi.AccelerationStructure;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout;
import org.lwjgl.vulkan.VkDevice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A type tag keeps Iris's existing enum binary-compatible while extending its descriptor semantics. */
public final class AccelerationStructureDescriptors {
    public static final String TYPE = "irisrt_accelerationStructureEXT";

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

    /** Carries strong resource references beside the native handles accepted by Iris's snapshot API. */
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
