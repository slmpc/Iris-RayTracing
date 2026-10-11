package com.github.slmpc.irisrt.mixin;

import com.github.slmpc.irisrt.scene.*;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.*;

@Mixin(SectionCompiler.Results.class)
public abstract class SectionCompilerResultsMixin implements TerrainGeometryAccess {
    @Unique private TerrainGeometry irisrt$geometry;
    @Override public TerrainGeometry irisrt$getTerrainGeometry() { return irisrt$geometry; }
    @Override public void irisrt$setTerrainGeometry(TerrainGeometry geometry) { irisrt$geometry = geometry; }
}
