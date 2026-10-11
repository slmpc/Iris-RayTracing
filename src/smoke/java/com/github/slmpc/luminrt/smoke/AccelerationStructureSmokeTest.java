package com.github.slmpc.luminrt.smoke;

import com.github.slmpc.luminrt.LuminRT;
import com.github.slmpc.luminrt.rhi.*;
import com.github.slmpc.luminrt.scene.SceneAccelerationStructure;
import com.github.slmpc.luminrt.scene.TriangleMesh;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.irisshaders.iris.Iris;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** 按需启用的集成测试。成功运行后写入标记文件并退出游戏。 */
public final class AccelerationStructureSmokeTest implements ClientModInitializer {
    private boolean ran;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Sodium 的完整设置在资源加载完成后注册，测试必须等到此时再执行。
            if (ran || !client.isGameLoadFinished()) return;
            ran = true;
            try {
                RayTracingSettingsSmokeTest.run();
                SodiumTerrainSmokeTest.run();
                run();
                java.nio.file.Files.writeString(java.nio.file.Path.of("as-smoke-passed.txt"), "AS smoke passed\n");
                LuminRT.LOGGER.info("LUMINRT_AS_SMOKE_PASSED");
            } catch (Exception | AssertionError failure) {
                LuminRT.LOGGER.error("LUMINRT_AS_SMOKE_FAILED", failure);
                throw new IllegalStateException("AS integration test failed", failure);
            } finally {
                client.stop();
            }
        });
    }

    private void run() {
        var backend = AccelerationStructureBackendProvider.get(Iris.getRenderBackend());
        if ("OPENGL".equals(System.getProperty("luminrt.smokeBackend"))) {
            require(backend == null, "OpenGL must return an empty AS backend");
            return;
        }
        require(backend != null && backend.isSupported(), "An AS-capable Vulkan GPU is required");
        // 场景路径覆盖空 TLAS、动态 refit、结构替换及删除最后一个实例。
        try (var scene = new SceneAccelerationStructure(backend)) {
            scene.build(0, 0, 0);
            var mesh = new TriangleMesh(new float[]{0,0,0, 1,0,0, 0,1,0}, new int[]{0,1,2}, true);
            scene.put("dynamic", List.of(mesh), 0, 0, 0, true);
            scene.build(0, 0, 0);
            scene.put("dynamic", List.of(mesh), 0, 0, 0, true);
            scene.build(1, 0, 0);
            scene.put("dynamic", List.of(mesh), 0, 0, 0, false);
            scene.build(1, 0, 0);
            scene.remove("dynamic");
            scene.build(1, 0, 0);
            awaitGpu();
        }
        ByteBuffer vertices = MemoryUtil.memAlloc(36).order(ByteOrder.nativeOrder());
        ByteBuffer instances = MemoryUtil.memCalloc(64).order(ByteOrder.nativeOrder());
        ByteBuffer aabbs = MemoryUtil.memAlloc(24).order(ByteOrder.nativeOrder());
        try (var vertexBuffer = backend.createInputBuffer(36);
             var instanceBuffer = backend.createInputBuffer(64);
             var aabbBuffer = backend.createInputBuffer(24)) {
            vertices.asFloatBuffer().put(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0});
            vertexBuffer.upload(0, vertices);
            var triangles = new AccelerationGeometry.Triangles(vertexBuffer, 0, 3, 12, null, 0,
                    AccelerationGeometry.IndexType.NONE, 1, true);
            try (var blas = backend.create(new AccelerationStructureDescription(AccelerationStructure.Type.BOTTOM_LEVEL,
                    List.of(triangles), true, true))) {
                expectFailure(() -> backend.update(blas), "Update before initial build must fail");
                backend.build(blas);
                require(blas.deviceAddress() != 0, "BLAS must have a device address");
                vertices.putFloat(12, 2);
                vertexBuffer.upload(0, vertices);
                backend.update(blas);
                var instance = VkAccelerationStructureInstanceKHR.create(MemoryUtil.memAddress(instances));
                instance.transform().matrix().put(new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0});
                instance.instanceCustomIndex(0).mask(0xff).instanceShaderBindingTableRecordOffset(0).flags(0)
                        .accelerationStructureReference(blas.deviceAddress());
                instanceBuffer.upload(0, instances);
                try (var tlas = backend.create(new AccelerationStructureDescription(AccelerationStructure.Type.TOP_LEVEL,
                        List.of(new AccelerationGeometry.Instances(instanceBuffer, 0, 1)), true, true))) {
                    backend.build(tlas);
                    backend.update(tlas);
                    require(tlas.deviceAddress() != 0, "TLAS must have a device address");
                    PipelineRayQuerySmokeTest.run(backend, blas, tlas);
                    awaitGpu();
                }
                blas.close();
                expectFailure(() -> backend.build(blas), "Build after close must fail");
            }
            aabbs.asFloatBuffer().put(new float[]{-1, -1, -1, 1, 1, 1});
            aabbBuffer.upload(0, aabbs);
            try (var blas = backend.create(new AccelerationStructureDescription(AccelerationStructure.Type.BOTTOM_LEVEL,
                    List.of(new AccelerationGeometry.Aabbs(aabbBuffer, 0, 24, 1, true)), false, false))) {
                backend.build(blas);
                expectFailure(() -> backend.update(blas), "Update without allowUpdate must fail");
                awaitGpu();
            }
        } finally {
            MemoryUtil.memFree(vertices);
            MemoryUtil.memFree(instances);
            MemoryUtil.memFree(aabbs);
        }
        // 推进延迟销毁流程，至少覆盖编码器的两次在途提交。
        for (int i = 0; i < 3; i++) awaitGpu();
    }

    private void awaitGpu() {
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        try (var fence = encoder.createFence()) {
            encoder.submit();
            require(fence.awaitCompletion(10_000_000_000L), "GPU submission timed out");
        }
    }

    private void expectFailure(Runnable operation, String message) {
        try { operation.run(); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
