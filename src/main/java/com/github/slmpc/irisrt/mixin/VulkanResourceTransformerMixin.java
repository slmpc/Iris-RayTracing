package com.github.slmpc.irisrt.mixin;

import com.github.slmpc.irisrt.vulkan.AccelerationStructureShaderLayout;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import net.irisshaders.iris.pipeline.transform.*;
import net.irisshaders.iris.pipeline.transform.vulkan.VulkanResourceTransformer;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Map;
import java.util.function.Predicate;

@Mixin(value = VulkanResourceTransformer.class, remap = false)
public abstract class VulkanResourceTransformerMixin {
    @WrapMethod(method = "transform(Lio/github/douira/glsl_transformer/ast/transform/ASTParser;Ljava/util/Map;Ljava/util/function/Predicate;Lnet/irisshaders/iris/pipeline/transform/ShaderResourceLayout;Ljava/util/function/Predicate;Z)Lnet/irisshaders/iris/pipeline/transform/ShaderResourceLayout;")
    private static ShaderResourceLayout irisrt$lower(ASTParser parser, Map<PatchShaderType, TranslationUnit> trees,
            Predicate<String> registered, ShaderResourceLayout frame, Predicate<String> dynamic, boolean vulkan,
            Operation<ShaderResourceLayout> original) {
        return AccelerationStructureShaderLayout.transform(trees, vulkan,
                () -> original.call(parser, trees, registered, frame, dynamic, vulkan));
    }
}
