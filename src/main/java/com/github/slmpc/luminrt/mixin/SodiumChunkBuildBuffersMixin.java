package com.github.slmpc.luminrt.mixin;

import com.github.slmpc.luminrt.scene.SodiumMeshAccess;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ChunkBuildBuffers.class, remap = false)
public abstract class SodiumChunkBuildBuffersMixin {
    @Shadow @Final private ChunkVertexType vertexType;

    @Inject(method = {"createMesh", "createModifiedTranslucentMesh"}, at = @At("RETURN"))
    private void luminrt$rememberFormat(CallbackInfoReturnable<BuiltSectionMeshParts> cir) {
        if (cir.getReturnValue() != null)
            ((SodiumMeshAccess)cir.getReturnValue()).luminrt$setVertexFormat(vertexType.getVertexFormat());
    }
}
