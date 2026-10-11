package com.github.slmpc.luminrt.mixin;

import com.github.slmpc.luminrt.scene.*;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.*;

@Mixin(SectionCompiler.Results.class)
public abstract class SectionCompilerResultsMixin implements TerrainGeometryAccess {
    @Unique private TerrainGeometry luminrt$geometry;
    @Override public TerrainGeometry luminrt$getTerrainGeometry() { return luminrt$geometry; }
    @Override public void luminrt$setTerrainGeometry(TerrainGeometry geometry) { luminrt$geometry = geometry; }
}
