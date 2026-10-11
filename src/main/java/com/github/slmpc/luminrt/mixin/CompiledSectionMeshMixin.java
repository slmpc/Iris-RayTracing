package com.github.slmpc.luminrt.mixin;

import com.github.slmpc.luminrt.scene.*;
import net.minecraft.client.renderer.chunk.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompiledSectionMesh.class)
public abstract class CompiledSectionMeshMixin implements TerrainGeometryAccess {
    @Unique private volatile TerrainGeometry luminrt$geometry;
    @Override public TerrainGeometry luminrt$getTerrainGeometry() { return luminrt$geometry; }
    @Override public void luminrt$setTerrainGeometry(TerrainGeometry geometry) { luminrt$geometry = geometry; }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void luminrt$copy(TranslucencyPointOfView view, SectionCompiler.Results results, long start, CallbackInfo ci) {
        luminrt$geometry = ((TerrainGeometryAccess)(Object)results).luminrt$getTerrainGeometry();
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void luminrt$release(CallbackInfo ci) { luminrt$geometry = null; }
}
