package com.github.slmpc.luminrt.mixin;

import com.github.slmpc.luminrt.scene.SodiumMeshAccess;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = BuiltSectionMeshParts.class, remap = false)
public abstract class SodiumBuiltSectionMeshPartsMixin implements SodiumMeshAccess {
    @Unique private VertexFormat luminrt$vertexFormat;
    @Override public VertexFormat luminrt$getVertexFormat() { return luminrt$vertexFormat; }
    @Override public void luminrt$setVertexFormat(VertexFormat format) { luminrt$vertexFormat = format; }
}
