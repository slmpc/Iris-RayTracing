package com.github.slmpc.luminrt.mixin;

import com.github.slmpc.luminrt.scene.WorldAccelerationStructures;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 优先于 Iris 的帧起始注入，保证 begin/setup 着色器能读取本帧的相机相对地形。
@Mixin(value = LevelRenderer.class, priority = 1100)
public abstract class LevelRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void luminrt$begin(GraphicsResourceAllocator allocator, boolean outline, CameraRenderState camera,
                              GpuBufferSlice fog, Vector4f color, boolean sky, boolean depth, CallbackInfo ci) {
        WorldAccelerationStructures.beginFrame(camera.pos);
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"))
    private void luminrt$beginDynamic(CallbackInfo ci) { WorldAccelerationStructures.beginDynamic(); }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;", shift = At.Shift.AFTER))
    private void luminrt$finishDynamic(CallbackInfo ci) { WorldAccelerationStructures.endDynamic(); }

    @Inject(method = "resetLevelRenderData", at = @At("HEAD"))
    private void luminrt$reset(CallbackInfo ci) { WorldAccelerationStructures.reset(true); }
}
