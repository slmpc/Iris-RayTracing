package com.github.slmpc.irisrt.vulkan;

import java.util.List;

public interface AccelerationStructureSnapshot {
    void retainAccelerationStructures(List<VulkanAccelerationStructure> structures);
}
