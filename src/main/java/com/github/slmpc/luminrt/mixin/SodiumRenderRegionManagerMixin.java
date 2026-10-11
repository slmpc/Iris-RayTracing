package com.github.slmpc.luminrt.mixin;

import com.github.slmpc.luminrt.scene.*;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;

@Mixin(value = RenderRegionManager.class, remap = false)
public abstract class SodiumRenderRegionManagerMixin {
    // 此处只接收 Sodium 已接受的结果，且临时 NativeBuffer 尚未被 destroy 释放。
    @Inject(method = "uploadResults(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V", at = @At("RETURN"))
    private void luminrt$publish(RenderRegion region, Collection<BuilderTaskOutput> outputs, UniformBufferManager uniforms, CallbackInfo ci) {
        for (var output : outputs) {
            // 透明排序输出只改变索引顺序，不替换地形 BLAS。
            if (!(output instanceof ChunkBuildOutput built) || built.section.isDisposed()) continue;
            var geometry = new ArrayList<TriangleMesh>();
            if (WorldAccelerationStructures.collectsTerrain()) {
                for (var pass : DefaultTerrainRenderPasses.ALL) {
                    var mesh = built.getMesh(pass);
                    if (mesh == null) continue;
                    var format = ((SodiumMeshAccess)mesh).luminrt$getVertexFormat();
                    if (format == null) throw new IllegalStateException("Missing Sodium terrain vertex format");
                    var copied = SodiumTerrainMesh.copy(mesh.getVertexData().getDirectBuffer(), format,
                            !pass.isTranslucent() && !pass.supportsFragmentDiscard());
                    if (copied != null) geometry.add(copied);
                }
            }
            WorldAccelerationStructures.terrainChanged(built.section,
                    new TerrainGeometry(built.section.getPosition().asLong(), geometry), WorldAccelerationStructures.epoch());
        }
    }
}
