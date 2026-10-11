package com.github.slmpc.irisrt.mixin;

import com.github.slmpc.irisrt.scene.WorldAccelerationStructures;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StagedVertexBuffer.Draw.class)
public abstract class StagedVertexBufferDrawMixin {
    @Inject(method = "append", at = @At("HEAD"))
    private void irisrt$capture(MeshData mesh, CallbackInfo ci) { WorldAccelerationStructures.capture(mesh); }
}
