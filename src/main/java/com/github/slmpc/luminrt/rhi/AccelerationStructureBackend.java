package com.github.slmpc.luminrt.rhi;

/** 仅限渲染线程调用的 RHI 操作，必须在渲染通道之外录制。 */
public interface AccelerationStructureBackend {
    boolean isSupported();
    default boolean supportsRayQuery() { return false; }
    AccelerationStructureBuffer createInputBuffer(long size);
    AccelerationStructure create(AccelerationStructureDescription description);
    void build(AccelerationStructure structure);
    void update(AccelerationStructure structure);
}
