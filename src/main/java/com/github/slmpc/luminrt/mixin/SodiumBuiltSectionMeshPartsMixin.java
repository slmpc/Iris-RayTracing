package com.github.slmpc.luminrt.mixin;

import com.github.slmpc.luminrt.scene.SodiumMeshAccess;
import com.github.slmpc.luminrt.scene.TriangleMesh;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.IdentityHashMap;
import java.util.Map;

@Mixin(value = BuiltSectionMeshParts.class, remap = false)
public abstract class SodiumBuiltSectionMeshPartsMixin implements SodiumMeshAccess {
    @Unique private VertexFormat luminrt$vertexFormat;
    @Unique private final Map<Object, TriangleMesh> luminrt$packed = new IdentityHashMap<>();

    @Override public VertexFormat luminrt$getVertexFormat() { return luminrt$vertexFormat; }
    @Override public void luminrt$setVertexFormat(VertexFormat format) { luminrt$vertexFormat = format; }

    @Override public void luminrt$pack(Object key, TriangleMesh mesh) { luminrt$packed.put(key, mesh); }

    @Override public TriangleMesh luminrt$takePacked(Object key) { return luminrt$packed.remove(key); }
}
