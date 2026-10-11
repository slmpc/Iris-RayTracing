package com.github.slmpc.irisrt.rhi;

import net.irisshaders.iris.backend.RenderBackend;

/** 通过 Mixin 扩展 Iris 的 RenderBackend。OpenGL 不提供加速结构后端。 */
public interface AccelerationStructureBackendProvider {
    default AccelerationStructureBackend accelerationStructures() { return null; }

    static AccelerationStructureBackend get(RenderBackend backend) {
        return ((AccelerationStructureBackendProvider) backend).accelerationStructures();
    }
}
