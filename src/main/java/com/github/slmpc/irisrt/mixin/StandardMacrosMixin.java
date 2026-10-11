package com.github.slmpc.irisrt.mixin;

import com.github.slmpc.irisrt.IrisRT;
import com.llamalad7.mixinextras.sugar.Local;
import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.helpers.StringPair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = StandardMacros.class, remap = false)
public abstract class StandardMacrosMixin {
    @Shadow
    private static void define(List<StringPair> defines, String key) {
        throw new AssertionError();
    }

    // 在 Iris 复制宏列表之前追加定义，复用原有 define 并保留返回流程。
    @Inject(method = "createStandardEnvironmentDefines", at = @At(value = "INVOKE",
            target = "Lcom/google/common/collect/ImmutableList;copyOf(Ljava/util/Collection;)Lcom/google/common/collect/ImmutableList;"))
    private static void irisrt$addExtensionMacro(CallbackInfoReturnable<ImmutableList<StringPair>> cir,
            @Local ArrayList<StringPair> standardDefines) {
        if (IrisRT.getConfig().isExtensionEnabled()) {
            define(standardDefines, "ENABLED_LUMIN_RAY_TRACING_EXTENSION");
        }
    }
}
