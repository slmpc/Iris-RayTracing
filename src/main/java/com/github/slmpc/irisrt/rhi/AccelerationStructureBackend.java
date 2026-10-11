package com.github.slmpc.irisrt.rhi;

/** Render-thread-only RHI operations, recorded outside a render pass. */
public interface AccelerationStructureBackend {
    boolean isSupported();
    AccelerationStructureBuffer createInputBuffer(long size);
    AccelerationStructure create(AccelerationStructureDescription description);
    void build(AccelerationStructure structure);
    void update(AccelerationStructure structure);
}
