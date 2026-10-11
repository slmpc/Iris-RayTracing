package com.github.slmpc.irisrt.mixin;

import com.github.slmpc.irisrt.rhi.AccelerationStructureBackendProvider;
import net.irisshaders.iris.backend.RenderBackend;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = RenderBackend.class, remap = false)
public interface RenderBackendMixin extends AccelerationStructureBackendProvider {
}
