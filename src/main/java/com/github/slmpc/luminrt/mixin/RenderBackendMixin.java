package com.github.slmpc.luminrt.mixin;

import com.github.slmpc.luminrt.rhi.AccelerationStructureBackendProvider;
import net.irisshaders.iris.backend.RenderBackend;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = RenderBackend.class, remap = false)
public interface RenderBackendMixin extends AccelerationStructureBackendProvider {
}
