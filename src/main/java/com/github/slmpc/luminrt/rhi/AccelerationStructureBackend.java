package com.github.slmpc.luminrt.rhi;

import java.nio.ByteBuffer;
import java.util.List;

/** 仅限渲染线程调用的 RHI 操作，必须在渲染通道之外录制。 */
public interface AccelerationStructureBackend {
    record Upload(AccelerationStructureBuffer target, long offset, ByteBuffer data) {
        public Upload {
            if (target == null || data == null) throw new NullPointerException("Upload target/data");
        }
    }

    boolean isSupported();
    default boolean supportsRayQuery() { return false; }
    AccelerationStructureBuffer createInputBuffer(long size);
    AccelerationStructure create(AccelerationStructureDescription description);
    void build(AccelerationStructure structure);
    void update(AccelerationStructure structure);

    /** 录制一批设备地址缓冲区上传；后端可合并 staging 分配与同步。 */
    default void upload(List<Upload> uploads) {
        for (var upload : uploads) upload.target().upload(upload.offset(), upload.data());
    }
}
