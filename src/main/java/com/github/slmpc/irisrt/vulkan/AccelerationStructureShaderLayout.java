package com.github.slmpc.irisrt.vulkan;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinFixedTypeSpecifier;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import net.irisshaders.iris.pipeline.transform.*;
import net.irisshaders.iris.pipeline.transform.vulkan.VulkanGlsl;

import java.util.*;
import java.util.function.Supplier;

/** 在 Iris 转换普通 uniform 前提取不透明的加速结构 uniform，再添加无冲突的描述符集合 0 绑定。 */
public final class AccelerationStructureShaderLayout {
    private AccelerationStructureShaderLayout() { }

    public static ShaderResourceLayout transform(Map<PatchShaderType, TranslationUnit> trees, boolean vulkan,
                                                 Supplier<ShaderResourceLayout> original) {
        var resources = new LinkedHashMap<String, Resource>();
        var declarations = new ArrayList<Declaration>();
        for (var entry : trees.entrySet()) {
            var tree = entry.getValue();
            for (var external : List.copyOf(tree.getChildren())) {
                if (!(external instanceof DeclarationExternalDeclaration wrapper)
                        || !(wrapper.getDeclaration() instanceof TypeAndInitDeclaration variables)
                        || !(variables.getType().getTypeSpecifier() instanceof BuiltinFixedTypeSpecifier fixed)
                        || fixed.type != BuiltinFixedTypeSpecifier.BuiltinType.ACCELERATION_STRUCTURE) continue;
                if (!vulkan) throw new UnsupportedOperationException("Acceleration structure shaders require Vulkan");
                if (!VulkanGlsl.hasStorage(variables.getType().getTypeQualifier(), StorageType.UNIFORM)) continue;
                for (var member : variables.getMembers()) {
                    var dimensions = VulkanGlsl.dimensions(tree.getRoot(), member.getArraySpecifier(), fixed.getArraySpecifier());
                    if (dimensions.size() > 1) throw new IllegalArgumentException("Only one-dimensional TLAS arrays are supported");
                    int count = dimensions.stream().reduce(1, Math::multiplyExact);
                    if (count <= 0) throw new IllegalArgumentException("TLAS arrays require a positive literal size");
                    String name = member.getName().getName();
                    var resource = resources.computeIfAbsent(name, unused -> new Resource(count, !dimensions.isEmpty()));
                    if (resource.count != count || resource.array != !dimensions.isEmpty()) {
                        throw new IllegalArgumentException("Conflicting TLAS declarations across stages: " + name);
                    }
                    resource.stages.add(entry.getKey());
                    tree.getRoot().indexBuildSession(() -> {
                        var split = new TypeAndInitDeclaration(variables.getType().cloneInto(tree.getRoot()),
                                java.util.stream.Stream.of(member.cloneInto(tree.getRoot())));
                        declarations.add(new Declaration(tree, new DeclarationExternalDeclaration(split), name));
                    });
                }
                wrapper.detachAndDelete();
            }
        }
        var layout = original.get();
        if (resources.isEmpty()) return layout;
        var descriptors = new ArrayList<>(layout.descriptors());
        int binding = descriptors.stream().filter(d -> d.set() == 0).mapToInt(ShaderResourceLayout.Descriptor::binding).max().orElse(-1) + 1;
        var assignments = new HashMap<String, Integer>();
        for (var entry : resources.entrySet()) {
            if (descriptors.stream().anyMatch(d -> d.name().equals(entry.getKey())) || layout.uniforms().containsKey(entry.getKey())) {
                throw new IllegalArgumentException("Conflicting TLAS resource name: " + entry.getKey());
            }
            assignments.put(entry.getKey(), binding);
            descriptors.add(new ShaderResourceLayout.Descriptor(entry.getKey(), ShaderResourceLayout.Kind.IMAGE,
                    AccelerationStructureDescriptors.TYPE, entry.getValue().count, 0, binding++, null, entry.getValue().stages));
        }
        for (var declaration : declarations) {
            var qualifier = ((TypeAndInitDeclaration) declaration.external.getDeclaration()).getType().getTypeQualifier();
            declaration.tree.getRoot().indexBuildSession(() -> {
                VulkanGlsl.removeLayout(qualifier, "location", "set", "binding");
                VulkanGlsl.setLayout(qualifier, "set", 0);
                VulkanGlsl.setLayout(qualifier, "binding", assignments.get(declaration.name));
                declaration.tree.injectNode(ASTInjectionPoint.BEFORE_DECLARATIONS, declaration.external);
            });
        }
        return new ShaderResourceLayout(layout.uniformBufferSize(), layout.uniforms(), descriptors);
    }

    private record Declaration(TranslationUnit tree, DeclarationExternalDeclaration external, String name) { }
    private static final class Resource {
        final int count;
        final boolean array;
        final Set<PatchShaderType> stages = EnumSet.noneOf(PatchShaderType.class);
        Resource(int count, boolean array) { this.count = count; this.array = array; }
    }
}
