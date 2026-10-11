package com.github.slmpc.irisrt.rhi;

import net.irisshaders.iris.backend.RenderBackend;

/** Added to Iris RenderBackend by Mixin. OpenGL deliberately exposes no AS backend. */
public interface AccelerationStructureBackendProvider {
    default AccelerationStructureBackend accelerationStructures() { return null; }

    static AccelerationStructureBackend get(RenderBackend backend) {
        return ((AccelerationStructureBackendProvider) backend).accelerationStructures();
    }
}
