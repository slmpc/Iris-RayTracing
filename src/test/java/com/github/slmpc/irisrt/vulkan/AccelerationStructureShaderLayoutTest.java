package com.github.slmpc.irisrt.vulkan;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.print.PrintType;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import net.irisshaders.iris.pipeline.transform.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AccelerationStructureShaderLayoutTest {
    private TranslationUnit parse(String uniforms) {
        return new SingleASTTransformer<>().parseSeparateTranslationUnit("#version 460\n#extension GL_EXT_ray_query : require\n" + uniforms + "\nvoid main() {}\n");
    }

    @Test
    void mergesStagesAndAllocatesBindingsAfterExistingSetZeroResources() {
        var vertex = parse("uniform accelerationStructureEXT scene;");
        var fragment = parse("layout(set=7, binding=17) uniform accelerationStructureEXT scene, others[2];");
        var original = new ShaderResourceLayout(0, Map.of(), List.of(
                new ShaderResourceLayout.Descriptor("result", ShaderResourceLayout.Kind.STORAGE_BUFFER, "block", 1, 0, 5, 0, Set.of(PatchShaderType.FRAGMENT))));
        var result = AccelerationStructureShaderLayout.transform(Map.of(PatchShaderType.VERTEX, vertex, PatchShaderType.FRAGMENT, fragment), true, () -> original);
        var scene = result.descriptors().get(1);
        var others = result.descriptors().get(2);
        assertTrue(AccelerationStructureDescriptors.isAccelerationStructure(scene));
        assertEquals(6, scene.binding());
        assertEquals(Set.of(PatchShaderType.VERTEX, PatchShaderType.FRAGMENT), scene.stages());
        assertEquals(7, others.binding());
        assertEquals(2, others.count());
        String glsl = ASTPrinter.print(PrintType.COMPACT, fragment);
        assertTrue(glsl.contains("accelerationStructureEXT"));
        assertFalse(glsl.contains("binding=17"));
        assertFalse(glsl.contains("set=7"));
    }

    @Test
    void rejectsCrossStageArrayMismatchAndMultidimensionalArrays() {
        assertThrows(IllegalArgumentException.class, () -> AccelerationStructureShaderLayout.transform(Map.of(
                PatchShaderType.VERTEX, parse("uniform accelerationStructureEXT scene;"),
                PatchShaderType.FRAGMENT, parse("uniform accelerationStructureEXT scene[2];")), true, this::empty));
        assertThrows(IllegalArgumentException.class, () -> AccelerationStructureShaderLayout.transform(Map.of(
                PatchShaderType.COMPUTE, parse("uniform accelerationStructureEXT scene[2][2];")), true, this::empty));
    }

    @Test
    void rejectsConflictingOrdinaryResourceNamesAndOpenGl() {
        var original = new ShaderResourceLayout(0, Map.of(), List.of(
                new ShaderResourceLayout.Descriptor("scene", ShaderResourceLayout.Kind.IMAGE, "IMAGE2D", 1, 0, 0, null, Set.of(PatchShaderType.COMPUTE))));
        assertThrows(IllegalArgumentException.class, () -> AccelerationStructureShaderLayout.transform(Map.of(
                PatchShaderType.COMPUTE, parse("uniform accelerationStructureEXT scene;")), true, () -> original));
        assertThrows(UnsupportedOperationException.class, () -> AccelerationStructureShaderLayout.transform(Map.of(
                PatchShaderType.FRAGMENT, parse("uniform accelerationStructureEXT scene;")), false, this::empty));
    }

    @Test
    void preservesLayoutsWithoutAccelerationStructures() {
        var original = empty();
        assertSame(original, AccelerationStructureShaderLayout.transform(Map.of(PatchShaderType.COMPUTE, parse("uniform float value;")), true, () -> original));
    }

    private ShaderResourceLayout empty() { return new ShaderResourceLayout(0, Map.of(), List.of()); }
}
