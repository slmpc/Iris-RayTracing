package com.github.slmpc.luminrt.smoke;

import com.github.slmpc.luminrt.rhi.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.*;
import net.irisshaders.iris.gl.buffer.*;
import net.irisshaders.iris.pipeline.transform.*;
import net.irisshaders.iris.pipeline.transform.vulkan.VulkanShaderPatcher;
import net.irisshaders.iris.samplers.*;
import net.irisshaders.iris.uniforms.buffer.BufferUniformHolder;
import org.joml.Vector2i;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;

import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

final class PipelineRayQuerySmokeTest {
    static void sceneUV() throws java.io.IOException {
        var config = com.github.slmpc.luminrt.LuminRT.getConfig();
        boolean enabled = config.isExtensionEnabled();
        Object key = new Object();
        var mesh = new com.github.slmpc.luminrt.scene.TriangleMesh(new float[]{0,0,0, 1,0,0, 0,1,0},
                new int[]{0,1,2}, new float[]{0,0, 1,0, 0,1}, true);
        String helper = java.nio.file.Files.readString(java.nio.file.Path.of("../../docs/examples/ray-query/shaders/lib/luminrt_scene_geometry.glsl"));
        String source = """
            #version 460
            #extension GL_EXT_ray_query : require
            layout(local_size_x=1) in;
            layout(binding=0, std430) buffer Result { uint value; };
            uniform accelerationStructureEXT luminrt_Scene;
            """ + helper + """
            void main() {
                rayQueryEXT q;
                rayQueryInitializeEXT(q, luminrt_Scene, gl_RayFlagsOpaqueEXT, 255u,
                    vec3(.25,.25,1), .001, vec3(0,0,-1), 10.0);
                while (rayQueryProceedEXT(q)) {}
                vec2 uv;
                bool valid = rayQueryGetIntersectionTypeEXT(q, true) == gl_RayQueryCommittedIntersectionTriangleEXT;
                if (valid) valid = luminrtGetHitUV(rayQueryGetIntersectionInstanceCustomIndexEXT(q,true),
                    rayQueryGetIntersectionGeometryIndexEXT(q,true), rayQueryGetIntersectionPrimitiveIndexEXT(q,true),
                    rayQueryGetIntersectionBarycentricsEXT(q,true), uv);
                value = valid ? uint(round(uv.x * 1000.0)) + 1000u * uint(round(uv.y * 1000.0)) : 0u;
            }
            """;
        try {
            config.setExtensionEnabled(true);
            com.github.slmpc.luminrt.scene.WorldAccelerationStructures.reset(true);
            com.github.slmpc.luminrt.scene.WorldAccelerationStructures.terrainChanged(key,
                new com.github.slmpc.luminrt.scene.TerrainGeometry(0, List.of(mesh)),
                com.github.slmpc.luminrt.scene.WorldAccelerationStructures.epoch());
            frame();
            var definitions = new Int2ObjectArrayMap<BuiltShaderStorageInfo>();
            definitions.put(0, new BuiltShaderStorageInfo(16, false, 1, 1, null));
            try (var pack = new PackSamplers(); var output = new ShaderStorageBufferHolder(definitions, 1,1)) {
                var sources = Map.of(PatchShaderType.COMPUTE, source);
                var resources = new ProgramResources(pack, VulkanShaderPatcher.discover(sources).layout());
                resources.addStorageBuffers(output);
                resources.finish();
                var patched = VulkanShaderPatcher.patch(sources, resources.getBindingPlan());
                var pipeline = Iris.getRenderBackend().createComputePipeline("luminrt_uv_smoke", patched, resources, new BufferUniformHolder(patched.layout()));
                try {
                    dispatch(pipeline);
                    require(read(output.getBuffer(0).buffer()) == 250250, "Automatic scene UV binding must interpolate (.25,.25)");
                    var changed = new com.github.slmpc.luminrt.scene.TriangleMesh(mesh.positions(), mesh.indices(), new float[]{.5f,0, 1,0, .5f,1}, true);
                    com.github.slmpc.luminrt.scene.WorldAccelerationStructures.terrainChanged(key,
                        new com.github.slmpc.luminrt.scene.TerrainGeometry(0,List.of(changed)),
                        com.github.slmpc.luminrt.scene.WorldAccelerationStructures.epoch());
                    frame();
                    dispatch(pipeline);
                    require(read(output.getBuffer(0).buffer()) == 250625, "UV replacement must invalidate descriptor reuse");
                    dispatch(pipeline);
                    require(read(output.getBuffer(0).buffer()) == 250625, "Cached UV descriptors must preserve values");
                    com.github.slmpc.luminrt.scene.WorldAccelerationStructures.terrainChanged(key,null,
                        com.github.slmpc.luminrt.scene.WorldAccelerationStructures.epoch());
                    frame();
                    dispatch(pipeline);
                    require(read(output.getBuffer(0).buffer()) == 0, "Empty scene UV table must remain valid");
                    com.github.slmpc.luminrt.LuminRT.LOGGER.info("LUMINRT_UV_SMOKE_PASSED");
                } finally { Iris.getRenderBackend().retire(pipeline::close); }
            }
        } finally {
            com.github.slmpc.luminrt.scene.WorldAccelerationStructures.reset(true);
            config.setExtensionEnabled(enabled);
        }
    }

    /** 模拟一帧的场景发布：begin 只兜底构建，动态准备完成后提交唯一一次构建。 */
    private static void frame() {
        com.github.slmpc.luminrt.scene.WorldAccelerationStructures.beginFrame(net.minecraft.world.phys.Vec3.ZERO);
        com.github.slmpc.luminrt.scene.WorldAccelerationStructures.beginDynamic();
        com.github.slmpc.luminrt.scene.WorldAccelerationStructures.endDynamic();
    }

    private static final String RAY = """
        rayQueryEXT q;
        rayQueryInitializeEXT(q, scene, gl_RayFlagsOpaqueEXT, 255,
            vec3(0.25, 0.25, 1.0), 0.001, vec3(0, 0, -1), 10.0);
        while (rayQueryProceedEXT(q)) {}
        uint hit = rayQueryGetIntersectionTypeEXT(q, true) == gl_RayQueryCommittedIntersectionTriangleEXT ? 1u : 0u;
        """;

    static void run(AccelerationStructureBackend asBackend, AccelerationStructure blas, AccelerationStructure tlas) {
        require(asBackend.supportsRayQuery(), "Device must support ray query");
        var data = MemoryUtil.memCalloc(64);
        try (var input = asBackend.createInputBuffer(64)) {
            var instance = VkAccelerationStructureInstanceKHR.create(MemoryUtil.memAddress(data));
            instance.transform().matrix().put(new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0});
            instance.mask(0).accelerationStructureReference(blas.deviceAddress());
            input.upload(0, data);
            try (var missTlas = asBackend.create(new AccelerationStructureDescription(AccelerationStructure.Type.TOP_LEVEL,
                    List.of(new AccelerationGeometry.Instances(input, 0, 1)), false, true))) {
                asBackend.build(missTlas);
                compute(asBackend, blas, tlas, missTlas, input);
                graphics(tlas);
            }
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static void compute(AccelerationStructureBackend asBackend, AccelerationStructure blas,
            AccelerationStructure tlas, AccelerationStructure missTlas, AccelerationStructureBuffer input) {
        String source = """
            #version 460
            #extension GL_EXT_ray_query : require
            layout(local_size_x=1, local_size_y=1, local_size_z=1) in;
            layout(binding=0, std430) buffer Result { uint value; };
            layout(binding=9) uniform accelerationStructureEXT scenes[2];
            void main() {
            """ + RAY.replace("q, scene,", "q, scenes[0],") + """
                rayQueryEXT second;
                rayQueryInitializeEXT(second, scenes[1], gl_RayFlagsOpaqueEXT, 255,
                    vec3(0.25, 0.25, 1.0), 0.001, vec3(0, 0, -1), 10.0);
                while (rayQueryProceedEXT(second)) {}
                uint other = rayQueryGetIntersectionTypeEXT(second, true) == gl_RayQueryCommittedIntersectionTriangleEXT ? 1u : 0u;
                value = hit + 2u * other;
            }
            """;
        var sources = Map.of(PatchShaderType.COMPUTE, source);
        var definitions = new Int2ObjectArrayMap<BuiltShaderStorageInfo>();
        definitions.put(0, new BuiltShaderStorageInfo(16, false, 1, 1, null));
        try (var pack = new PackSamplers(); var output = new ShaderStorageBufferHolder(definitions, 1, 1)) {
            var discovery = VulkanShaderPatcher.discover(sources);
            var resources = new ProgramResources(pack, discovery.layout());
            resources.addStorageBuffers(output);
            var bindings = AccelerationStructureBindings.of(resources);
            expectFailure(resources::finish, "Unregistered TLAS must fail resource finalization");
            expectFailure(() -> bindings.setAccelerationStructure("unknown", () -> tlas), "Undeclared TLAS name must fail");
            var selected = new AtomicReference<>(List.of(tlas, missTlas));
            bindings.setAccelerationStructures("scenes", selected::get);
            resources.finish();
            var patched = VulkanShaderPatcher.patch(sources, resources.getBindingPlan());
            var uniforms = new BufferUniformHolder(patched.layout());
            var pipeline = Iris.getRenderBackend().createComputePipeline("luminrt_ray_query_smoke", patched, resources, uniforms);
            try {
                dispatch(pipeline);
                require(read(output.getBuffer(0).buffer()) == 1, "Compute ray query must distinguish hit/miss TLAS array elements");
                selected.set(List.of(missTlas, tlas));
                dispatch(pipeline);
                require(read(output.getBuffer(0).buffer()) == 2, "TLAS replacement must invalidate descriptor snapshot reuse");
                // 重复使用相同绑定，以验证 Iris 的描述符缓存。
                dispatch(pipeline);
                require(read(output.getBuffer(0).buffer()) == 2, "Cached descriptor snapshot must preserve TLAS bindings");
                selected.set(List.of(tlas));
                expectFailure(resources::resolve, "Wrong TLAS array length must fail");
                selected.set(List.of(blas, tlas));
                expectFailure(() -> dispatch(pipeline), "BLAS shader binding must fail");
                try (var unbuilt = asBackend.create(new AccelerationStructureDescription(AccelerationStructure.Type.TOP_LEVEL,
                        List.of(new AccelerationGeometry.Instances(input, 0, 1)), false, true))) {
                    selected.set(List.of(unbuilt, tlas));
                    expectFailure(() -> dispatch(pipeline), "Unbuilt TLAS shader binding must fail");
                    asBackend.build(unbuilt);
                    selected.set(List.of(unbuilt, tlas));
                    // 录制着色器命令后，在提交前关闭资源；快照租约必须保证资源仍然有效。
                    dispatch(pipeline);
                    unbuilt.close();
                    require(read(output.getBuffer(0).buffer()) == 2, "Closing a leased TLAS must preserve pending shader work");
                    expectFailure(() -> dispatch(pipeline), "Closed TLAS cannot be used by a new descriptor snapshot");
                }
                // 被拒绝的绑定不得影响后续有效快照。
                selected.set(List.of(tlas, missTlas));
                dispatch(pipeline);
                require(read(output.getBuffer(0).buffer()) == 1, "Pipeline must recover after rejected TLAS binding");
            } finally {
                Iris.getRenderBackend().retire(pipeline::close);
            }
        }
    }

    private static void graphics(AccelerationStructure tlas) {
        String fragment = """
            #version 460
            #extension GL_EXT_ray_query : require
            uniform accelerationStructureEXT scene;
            layout(binding=0, std430) buffer Result { uint value; };
            layout(location=0) out vec4 color;
            void main() {
            """ + RAY + "value = hit; color = vec4(float(hit), 0, 0, 1); }";
        String vertex = """
            #version 460
            void main() {
                vec2 positions[3] = vec2[3](vec2(-1,-1), vec2(3,-1), vec2(-1,3));
                gl_Position = vec4(positions[gl_VertexID], 0, 1);
            }
            """;
        var sources = Map.of(PatchShaderType.VERTEX, vertex, PatchShaderType.FRAGMENT, fragment);
        var definitions = new Int2ObjectArrayMap<BuiltShaderStorageInfo>();
        definitions.put(0, new BuiltShaderStorageInfo(16, false, 1, 1, null));
        var device = RenderSystem.getDevice();
        try (var pack = new PackSamplers(); var output = new ShaderStorageBufferHolder(definitions, 1, 1);
             var color = device.createTexture("LuminRT ray query smoke", GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
             var view = device.createTextureView(color);
             var vertices = device.createBuffer(() -> "LuminRT dummy vertices", GpuBuffer.USAGE_VERTEX, 16)) {
            var discovered = VulkanShaderPatcher.discover(sources);
            var resources = new ProgramResources(pack, discovered.layout());
            resources.addStorageBuffers(output);
            AccelerationStructureBindings.of(resources).setAccelerationStructure("scene", () -> tlas);
            resources.finish();
            var patched = VulkanShaderPatcher.patch(sources, resources.getBindingPlan());
            var uniforms = new BufferUniformHolder(patched.layout());
            var backend = Iris.getRenderBackend();
            var shader = backend.createGraphicsShader("luminrt_fragment_ray_smoke", patched, resources, uniforms);
            var framebuffer = new FramebufferDescription(List.of(GpuFormat.RGBA8_UNORM), null);
            var pipeline = shader.createPipeline(new PipelineState(framebuffer, Collections.singletonList(null), List.of(), 0, null, false, null));
            try {
                backend.createSync().read(pipeline).nextIs(SyncCommand.Stage.GRAPHICS).run();
                try (var pass = backend.createRenderPass(framebuffer, List.of(view), null, new Vector2i(1,1))) {
                    pass.bindPipeline(pipeline);
                    pass.setVertexBuffer(0, vertices.slice());
                    pass.draw(3,1,0,0);
                }
                require(read(output.getBuffer(0).buffer()) == 1, "Fragment ray query must hit bound TLAS");
            } finally {
                backend.retire(pipeline::close);
                backend.retire(shader::close);
            }
        }
    }

    private static void dispatch(ComputePipeline pipeline) {
        Iris.getRenderBackend().commands().dispatch(pipeline, new Vector3i(1,1,1), null);
    }

    private static int read(GpuBuffer output) {
        var device = RenderSystem.getDevice();
        try (var readback = device.createBuffer(() -> "LuminRT ray query result", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 16)) {
            var encoder = device.createCommandEncoder();
            encoder.copyToBuffer(output.slice(), readback.slice());
            try (var fence = encoder.createFence()) {
                encoder.submit();
                require(fence.awaitCompletion(10_000_000_000L), "Ray query GPU work timed out");
            }
            try (GpuBufferSlice.MappedView mapped = readback.map(true, false)) {
                return mapped.data().order(ByteOrder.nativeOrder()).getInt();
            }
        }
    }

    private static void expectFailure(Runnable operation, String message) {
        try { operation.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
