package com.github.slmpc.irisrt.mixin;

import com.github.slmpc.irisrt.scene.WorldAccelerationStructures;
import net.minecraft.client.renderer.chunk.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionMixin {
    @Unique private final long irisrt$epoch = WorldAccelerationStructures.epoch();

    @Inject(method = "setSectionMesh", at = @At("RETURN"))
    private void irisrt$publish(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> cir) {
        WorldAccelerationStructures.sectionChanged((SectionRenderDispatcher.RenderSection)(Object)this, irisrt$epoch);
    }

    @Inject(method = "reset", at = @At("RETURN"))
    private void irisrt$remove(CallbackInfo ci) {
        WorldAccelerationStructures.sectionChanged((SectionRenderDispatcher.RenderSection)(Object)this, irisrt$epoch);
    }
}
