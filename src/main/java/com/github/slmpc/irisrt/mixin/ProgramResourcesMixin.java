package com.github.slmpc.irisrt.mixin;

import com.github.slmpc.irisrt.rhi.*;
import com.github.slmpc.irisrt.vulkan.AccelerationStructureDescriptors;
import com.github.slmpc.irisrt.scene.WorldAccelerationStructures;
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
    @Unique private final Map<String, ShaderResourceLayout.Descriptor> irisrt$declarations = new LinkedHashMap<>();
    @Unique private final Map<String, Supplier<List<AccelerationStructure>>> irisrt$bindings = new LinkedHashMap<>();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void irisrt$separateAccelerationStructures(PackSamplers pack, ShaderResourceLayout layout, CallbackInfo ci) {
        for (var descriptor : layout.descriptors()) {
            if (AccelerationStructureDescriptors.isAccelerationStructure(descriptor)) {
                irisrt$declarations.put(descriptor.name(), descriptor);
                imageDeclarations.remove(descriptor.name());
                if (WorldAccelerationStructures.SHADER_NAME.equals(descriptor.name()) && descriptor.count() == 1) {
                    irisrt$bindings.put(descriptor.name(), () -> List.of(WorldAccelerationStructures.current()));
                }
            }
        }
    }

    @Override
    public void setAccelerationStructures(String name, Supplier<List<AccelerationStructure>> structures) {
        if (!irisrt$declarations.containsKey(name)) throw new IllegalArgumentException("No AS declaration named " + name);
        irisrt$bindings.put(name, Objects.requireNonNull(structures));
    }

    @Inject(method = "finish", at = @At("RETURN"))
    private void irisrt$checkRegistration(CallbackInfo ci) {
        if (!irisrt$bindings.keySet().equals(irisrt$declarations.keySet())) {
            throw new IllegalStateException("Missing TLAS registrations: " + irisrt$declarations.keySet());
        }
    }

    @Override
    public Map<String, List<AccelerationStructure>> resolveAccelerationStructures() {
        var result = new LinkedHashMap<String, List<AccelerationStructure>>();
        for (var entry : irisrt$declarations.entrySet()) {
            var supplier = irisrt$bindings.get(entry.getKey());
            if (supplier == null) throw new IllegalStateException("No TLAS registered for " + entry.getKey());
            var values = List.copyOf(supplier.get());
            if (values.size() != entry.getValue().count()) throw new IllegalArgumentException("Wrong TLAS array size: " + entry.getKey());
            result.put(entry.getKey(), values);
        }
        return Map.copyOf(result);
    }

    @Inject(method = "resolve(Ljava/util/Map;)Lnet/irisshaders/iris/samplers/ProgramResources$Bindings;", at = @At("RETURN"))
    private void irisrt$resolve(CallbackInfoReturnable<ProgramResources.Bindings> cir) {
        ((ResolvedAccelerationStructures) (Object) cir.getReturnValue()).setAccelerationStructures(resolveAccelerationStructures());
    }
}
