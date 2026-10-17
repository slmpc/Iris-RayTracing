package com.github.slmpc.luminrt.mixin;

import com.github.slmpc.luminrt.scene.SodiumMeshAccess;
import com.github.slmpc.luminrt.scene.SodiumTerrainMesh;
import com.github.slmpc.luminrt.scene.WorldAccelerationStructures;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.bsp_tree.UpdatedQuadsList;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium 的合并网格在构建线程完成；在这里保存实际格式并预先解码几何，
 * 渲染线程只负责发布，不再逐顶点解码。
 */
@Mixin(value = ChunkBuildBuffers.class, remap = false)
public abstract class SodiumChunkBuildBuffersMixin {
    @Shadow @Final private ChunkVertexType vertexType;

    @Inject(method = "createMesh", at = @At("RETURN"))
    private void luminrt$packMesh(TerrainRenderPass pass, int visibleSlices, boolean forceUnassigned,
                                  boolean sliceReordering, CallbackInfoReturnable<BuiltSectionMeshParts> cir) {
        luminrt$pack(cir.getReturnValue(), pass, !pass.isTranslucent());
    }

    @Inject(method = "createModifiedTranslucentMesh", at = @At("RETURN"))
    private void luminrt$packModifiedTranslucent(UpdatedQuadsList updatedQuads,
                                                 CallbackInfoReturnable<BuiltSectionMeshParts> cir) {
        luminrt$pack(cir.getReturnValue(), SodiumMeshAccess.MODIFIED_TRANSLUCENT, false);
    }

    private void luminrt$pack(BuiltSectionMeshParts parts, Object key, boolean opaque) {
        if (parts == null) return;
        var access = (SodiumMeshAccess)parts;
        var format = vertexType.getVertexFormat();
        access.luminrt$setVertexFormat(format);
        if (!WorldAccelerationStructures.collectsTerrain() || parts.getVertexData() == null) return;
        try {
            var copied = SodiumTerrainMesh.copy(parts.getVertexData().getDirectBuffer(), format, opaque);
            if (copied != null) access.luminrt$pack(key, copied);
        } catch (RuntimeException failure) {
            // 构建线程无法安全报告上游错误；渲染线程会用相同入参回退解码并保留原诊断。
        }
    }
}
