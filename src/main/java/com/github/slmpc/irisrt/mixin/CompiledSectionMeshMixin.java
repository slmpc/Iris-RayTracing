package com.github.slmpc.irisrt.mixin;

import com.github.slmpc.irisrt.scene.*;
import net.minecraft.client.renderer.chunk.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompiledSectionMesh.class)
public abstract class CompiledSectionMeshMixin implements TerrainGeometryAccess {
    @Unique private volatile TerrainGeometry irisrt$geometry;
    @Override public TerrainGeometry irisrt$getTerrainGeometry() { return irisrt$geometry; }
    @Override public void irisrt$setTerrainGeometry(TerrainGeometry geometry) { irisrt$geometry = geometry; }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void irisrt$copy(TranslucencyPointOfView view, SectionCompiler.Results results, long start, CallbackInfo ci) {
        irisrt$geometry = ((TerrainGeometryAccess)(Object)results).irisrt$getTerrainGeometry();
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void irisrt$release(CallbackInfo ci) { irisrt$geometry = null; }
}
