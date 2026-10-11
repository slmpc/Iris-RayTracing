package com.github.slmpc.irisrt.mixin;

import com.github.slmpc.irisrt.scene.*;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.ArrayList;

@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
    @Inject(method = "compile", at = @At("RETURN"))
    private void irisrt$capture(SectionPos pos, RenderSectionRegion region, VertexSorting sorting,
                                SectionBufferBuilderPack buffers, CallbackInfoReturnable<SectionCompiler.Results> cir) {
        if (!WorldAccelerationStructures.collectsTerrain()) return;
        var geometry = new ArrayList<TriangleMesh>();
        cir.getReturnValue().renderedLayers.forEach((layer, mesh) -> {
            var copied = TriangleMesh.copy(mesh, layer == ChunkSectionLayer.SOLID);
            if (copied != null) geometry.add(copied);
        });
        ((TerrainGeometryAccess)(Object)cir.getReturnValue()).irisrt$setTerrainGeometry(new TerrainGeometry(pos.asLong(), geometry));
    }
}
