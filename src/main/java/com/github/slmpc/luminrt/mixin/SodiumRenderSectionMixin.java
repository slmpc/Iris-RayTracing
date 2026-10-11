package com.github.slmpc.luminrt.mixin;

import com.github.slmpc.luminrt.scene.WorldAccelerationStructures;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSection.class, remap = false)
public abstract class SodiumRenderSectionMixin {
    @Inject(method = "delete", at = @At("RETURN"))
    private void luminrt$unload(CallbackInfo ci) {
        WorldAccelerationStructures.terrainChanged(this, null, WorldAccelerationStructures.epoch());
    }
}
