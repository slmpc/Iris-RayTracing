package com.github.slmpc.luminrt.mixin;

import com.github.slmpc.luminrt.scene.WorldAccelerationStructures;
import net.minecraft.client.renderer.chunk.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionMixin {
    @Unique private final long luminrt$epoch = WorldAccelerationStructures.epoch();

    @Inject(method = "setSectionMesh", at = @At("RETURN"))
    private void luminrt$publish(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> cir) {
        WorldAccelerationStructures.sectionChanged((SectionRenderDispatcher.RenderSection)(Object)this, luminrt$epoch);
    }

    @Inject(method = "reset", at = @At("RETURN"))
    private void luminrt$remove(CallbackInfo ci) {
        WorldAccelerationStructures.sectionChanged((SectionRenderDispatcher.RenderSection)(Object)this, luminrt$epoch);
    }
}
