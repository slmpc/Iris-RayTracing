package com.github.slmpc.irisrt.mixin;

import com.github.slmpc.irisrt.scene.WorldAccelerationStructures;
import net.irisshaders.iris.pipeline.PipelineManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PipelineManager.class, remap = false)
public abstract class PipelineManagerMixin {
    @Inject(method = "destroyPipeline", at = @At("HEAD"))
    private void irisrt$resetScene(CallbackInfo ci) { WorldAccelerationStructures.reset(false); }
}
