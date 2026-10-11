package com.github.slmpc.irisrt.mixin;

import com.github.slmpc.irisrt.rhi.AccelerationStructureBackend;
import com.github.slmpc.irisrt.rhi.AccelerationStructureBackendProvider;
import com.github.slmpc.irisrt.vulkan.VulkanAccelerationStructureBackend;
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
    private AccelerationStructureBackend irisrt$accelerationStructures;

    @Override
    public AccelerationStructureBackend accelerationStructures() {
        if (closed) throw new IllegalStateException("Vulkan render backend is closed");
        if (irisrt$accelerationStructures == null) {
            irisrt$accelerationStructures = new VulkanAccelerationStructureBackend(VulkanCommandBridge.getDevice());
        }
        return irisrt$accelerationStructures;
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void irisrt$closeAccelerationStructures(CallbackInfo ci) {
        if (irisrt$accelerationStructures instanceof VulkanAccelerationStructureBackend backend) backend.close();
    }
}
