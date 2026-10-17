package com.github.slmpc.luminrt.mixin;

import com.github.slmpc.luminrt.rhi.*;
import com.github.slmpc.luminrt.vulkan.AccelerationStructureDescriptors;
import com.github.slmpc.luminrt.scene.WorldAccelerationStructures;
import com.github.slmpc.luminrt.scene.SceneGeometryDirectory;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout;
import net.irisshaders.iris.samplers.PackSamplers;
import net.irisshaders.iris.samplers.ProgramResources;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

import java.util.*;
import java.util.function.Supplier;

@Mixin(value = ProgramResources.class, remap = false)
public abstract class ProgramResourcesMixin implements AccelerationStructureBindings {
    @Shadow @Final private Map<String, ShaderResourceLayout.Descriptor> imageDeclarations;
    @Shadow @Final @Mutable private List<ShaderResourceLayout.Descriptor> storageDeclarations;
    @Unique private boolean luminrt$sceneGeometry;
    @Unique private boolean luminrt$sceneRecords;
    @Unique private final Map<String, ShaderResourceLayout.Descriptor> luminrt$declarations = new LinkedHashMap<>();
    @Unique private final Map<String, Supplier<List<AccelerationStructure>>> luminrt$bindings = new LinkedHashMap<>();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void luminrt$separateAccelerationStructures(PackSamplers pack, ShaderResourceLayout layout, CallbackInfo ci) {
        for (var descriptor : storageDeclarations) {
            if (SceneGeometryDirectory.SHADER_NAME.equals(descriptor.name())) {
                if (descriptor.count() != 1) throw new IllegalArgumentException("luminrt_SceneGeometry must be a scalar storage block");
                luminrt$sceneGeometry = true;
            }
            if (SceneGeometryDirectory.RECORDS_SHADER_NAME.equals(descriptor.name())) {
                if (descriptor.count() != 1) throw new IllegalArgumentException("luminrt_SceneRecords must be a scalar storage block");
                luminrt$sceneRecords = true;
            }
        }
        storageDeclarations = storageDeclarations.stream()
                .filter(d -> !SceneGeometryDirectory.SHADER_NAME.equals(d.name())
                        && !SceneGeometryDirectory.RECORDS_SHADER_NAME.equals(d.name())).toList();
        for (var descriptor : layout.descriptors()) {
            if (AccelerationStructureDescriptors.isAccelerationStructure(descriptor)) {
                luminrt$declarations.put(descriptor.name(), descriptor);
                imageDeclarations.remove(descriptor.name());
                if (WorldAccelerationStructures.SHADER_NAME.equals(descriptor.name()) && descriptor.count() == 1) {
                    luminrt$bindings.put(descriptor.name(), () -> List.of(WorldAccelerationStructures.current()));
                }
            }
        }
    }

    @Inject(method = "getBufferBindings", at = @At("RETURN"))
    private void luminrt$bindSceneBuffers(CallbackInfoReturnable<Map<String, List<GpuBufferSlice>>> cir) {
        if (luminrt$sceneGeometry) cir.getReturnValue().put(SceneGeometryDirectory.SHADER_NAME, List.of(WorldAccelerationStructures.currentGeometry()));
        if (luminrt$sceneRecords) cir.getReturnValue().put(SceneGeometryDirectory.RECORDS_SHADER_NAME, List.of(WorldAccelerationStructures.currentRecords()));
    }

    @Override
    public void setAccelerationStructures(String name, Supplier<List<AccelerationStructure>> structures) {
        if (!luminrt$declarations.containsKey(name)) throw new IllegalArgumentException("No AS declaration named " + name);
        luminrt$bindings.put(name, Objects.requireNonNull(structures));
    }

    @Inject(method = "finish", at = @At("RETURN"))
    private void luminrt$checkRegistration(CallbackInfo ci) {
        if (!luminrt$bindings.keySet().equals(luminrt$declarations.keySet())) {
            throw new IllegalStateException("Missing TLAS registrations: " + luminrt$declarations.keySet());
        }
    }

    @Override
    public Map<String, List<AccelerationStructure>> resolveAccelerationStructures() {
        var result = new LinkedHashMap<String, List<AccelerationStructure>>();
        for (var entry : luminrt$declarations.entrySet()) {
            var supplier = luminrt$bindings.get(entry.getKey());
            if (supplier == null) throw new IllegalStateException("No TLAS registered for " + entry.getKey());
            var values = List.copyOf(supplier.get());
            if (values.size() != entry.getValue().count()) throw new IllegalArgumentException("Wrong TLAS array size: " + entry.getKey());
            result.put(entry.getKey(), values);
        }
        return Map.copyOf(result);
    }

    @Inject(method = "resolve(Ljava/util/Map;)Lnet/irisshaders/iris/samplers/ProgramResources$Bindings;", at = @At("RETURN"))
    private void luminrt$resolve(CallbackInfoReturnable<ProgramResources.Bindings> cir) {
        ((ResolvedAccelerationStructures) (Object) cir.getReturnValue()).setAccelerationStructures(resolveAccelerationStructures());
    }
}
