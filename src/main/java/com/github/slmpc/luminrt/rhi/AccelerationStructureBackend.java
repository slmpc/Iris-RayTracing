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

    /** 一次批量构建/更新请求；同一批内先构建 BLAS，最后才是引用它们的 TLAS。 */
    record Build(AccelerationStructure structure, boolean update) {
        public Build {
            if (structure == null) throw new NullPointerException("Build structure");
        }
    }

    boolean isSupported();
    default boolean supportsRayQuery() { return false; }
    AccelerationStructureBuffer createInputBuffer(long size);
    AccelerationStructure create(AccelerationStructureDescription description);
    void build(AccelerationStructure structure);
    void update(AccelerationStructure structure);

    /** 录制一批构建/更新；后端可复用 scratch 并用一次多结构构建调用提交。 */
    default void buildBatch(List<Build> builds) {
        for (var build : builds) {
            if (build.update()) update(build.structure());
            else build(build.structure());
        }
    }

    /** 录制一批设备地址缓冲区上传；后端可合并 staging 分配与同步。 */
    default void upload(List<Upload> uploads) {
        for (var upload : uploads) upload.target().upload(upload.offset(), upload.data());
    }
}
