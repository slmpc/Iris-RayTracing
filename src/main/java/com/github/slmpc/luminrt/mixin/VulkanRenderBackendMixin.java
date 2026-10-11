package com.github.slmpc.luminrt.mixin;

import com.github.slmpc.luminrt.rhi.AccelerationStructureBackend;
import com.github.slmpc.luminrt.rhi.AccelerationStructureBackendProvider;
import com.github.slmpc.luminrt.vulkan.VulkanAccelerationStructureBackend;
import net.irisshaders.iris.vulkan.VulkanCommandBridge;
import net.irisshaders.iris.vulkan.VulkanRenderBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VulkanRenderBackend.class, remap = false)
public abstract class VulkanRenderBackendMixin implements AccelerationStructureBackendProvider {
    @Shadow
    private boolean closed;
    @Unique
    private AccelerationStructureBackend luminrt$accelerationStructures;

    @Override
    public AccelerationStructureBackend accelerationStructures() {
        if (closed) throw new IllegalStateException("Vulkan render backend is closed");
        if (luminrt$accelerationStructures == null) {
            luminrt$accelerationStructures = new VulkanAccelerationStructureBackend(VulkanCommandBridge.getDevice());
        }
        return luminrt$accelerationStructures;
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void luminrt$closeAccelerationStructures(CallbackInfo ci) {
        if (luminrt$accelerationStructures instanceof VulkanAccelerationStructureBackend backend) backend.close();
    }
}
