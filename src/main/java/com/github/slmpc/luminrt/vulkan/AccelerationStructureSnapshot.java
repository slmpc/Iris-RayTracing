package com.github.slmpc.luminrt.vulkan;

import java.util.List;

public interface AccelerationStructureSnapshot {
    void retainAccelerationStructures(List<VulkanAccelerationStructure> structures);
}
