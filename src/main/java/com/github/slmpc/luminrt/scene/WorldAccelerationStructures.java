package com.github.slmpc.luminrt.scene;

import com.github.slmpc.luminrt.LuminRT;
import com.github.slmpc.luminrt.rhi.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import net.irisshaders.iris.Iris;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 工作线程只发布 CPU 网格事件；资源创建、构建及释放始终在渲染线程执行。 */
public final class WorldAccelerationStructures {
    public static final String SHADER_NAME = "luminrt_Scene";
    private static final Object DYNAMIC = new Object();
    private static final AtomicLong EPOCH = new AtomicLong();
    private static final Object REGISTRY_LOCK = new Object();
    private static final Set<RenderSection> DIRTY = ConcurrentHashMap.newKeySet();
    private static final TerrainGeometryRegistry TERRAIN = new TerrainGeometryRegistry();
    private static final Map<RenderSection, SectionMesh> RESIDENT = new ConcurrentHashMap<>();
    private static final Map<RenderSection, SectionMesh> UPLOADED = new IdentityHashMap<>();
    private static final List<TriangleMesh> DYNAMIC_MESHES = new ArrayList<>();
    private static SceneAccelerationStructure scene;
    private static AccelerationStructureBackend owner;
    private static Vec3 camera = Vec3.ZERO;
    private static boolean captureDynamic;
    private static volatile boolean collectTerrain;

    private WorldAccelerationStructures() {}

    public static long epoch() { return EPOCH.get(); }
    public static boolean collectsTerrain() { return LuminRT.getConfig().isExtensionEnabled(); }

    /** 在 Sodium 完成区段网格上传后接收稳定的 CPU 几何快照。 */
    public static void terrainChanged(Object key, TerrainGeometry geometry, long epoch) {
        TERRAIN.publish(key, geometry, epoch);
    }

    public static void sectionChanged(RenderSection section, long epoch) {
        synchronized (REGISTRY_LOCK) {
            if (epoch != EPOCH.get()) return;
            var mesh = section.getSectionMesh();
            if (mesh instanceof TerrainGeometryAccess access && access.luminrt$getTerrainGeometry() != null)
                RESIDENT.put(section, mesh);
            else RESIDENT.remove(section);
            if (collectTerrain) DIRTY.add(section);
        }
    }

    public static void beginFrame(Vec3 position) {
        RenderSystem.assertOnRenderThread();
        camera = position;
        captureDynamic = false;
        DYNAMIC_MESHES.clear();
        if (!ensureScene()) return;
        flushTerrain();
        scene.build(camera.x, camera.y, camera.z);
    }

    public static void beginDynamic() {
        RenderSystem.assertOnRenderThread();
        captureDynamic = scene != null && collectTerrain;
        DYNAMIC_MESHES.clear();
    }

    public static void capture(MeshData mesh) {
        if (!captureDynamic) return;
        var copied = TriangleMesh.copy(mesh, false);
        if (copied != null) DYNAMIC_MESHES.add(copied);
    }

    public static void endDynamic() {
        RenderSystem.assertOnRenderThread();
        if (!captureDynamic) return;
        captureDynamic = false;
        try {
            scene.put(DYNAMIC, DYNAMIC_MESHES, camera.x, camera.y, camera.z, true);
            flushTerrain();
            scene.build(camera.x, camera.y, camera.z);
        } finally { DYNAMIC_MESHES.clear(); }
    }

    public static AccelerationStructure current() {
        RenderSystem.assertOnRenderThread();
        if (!ensureScene()) throw new IllegalStateException("luminrt_Scene requires the enabled ray tracing extension and an AS-capable Vulkan backend");
        // setup/begin 程序也允许绑定空场景；正常世界帧已在渲染通道创建前完成构建。
        return scene.build(camera.x, camera.y, camera.z);
    }

    public static SceneAccelerationStructure.Statistics statistics() {
        RenderSystem.assertOnRenderThread();
        return scene == null ? new SceneAccelerationStructure.Statistics(0, 0, 0, 0, 0) : scene.statistics();
    }

    /** 已确认上传且保存 CPU 快照的 Sodium 区段数量，不包含动态实例。 */
    public static int terrainSectionCount() { return TERRAIN.size(); }

    private static boolean ensureScene() {
        if (!LuminRT.getConfig().isExtensionEnabled()) {
            reset(false);
            return false;
        }
        var backend = AccelerationStructureBackendProvider.get(Iris.getRenderBackend());
        if (backend == null || !backend.isSupported()) { reset(false); return false; }
        if (owner != backend) { reset(false); owner = backend; }
        if (scene == null) { scene = new SceneAccelerationStructure(backend); TERRAIN.replay(); DIRTY.addAll(RESIDENT.keySet()); }
        collectTerrain = true;
        return true;
    }

    private static void flushTerrain() {
        for (var change : TERRAIN.drain()) {
            if (change.geometry() == null) scene.remove(change.key());
            else {
                var node = change.geometry().sectionNode();
                scene.put(change.key(), change.geometry().meshes(),
                        SectionPos.sectionToBlockCoord(SectionPos.x(node)),
                        SectionPos.sectionToBlockCoord(SectionPos.y(node)),
                        SectionPos.sectionToBlockCoord(SectionPos.z(node)), false);
            }
        }
        for (var section : DIRTY.toArray(RenderSection[]::new)) {
            // 先取走事件，随后发生的发布会留下新事件，下一次刷新仍能看到它。
            if (!DIRTY.remove(section)) continue;
            var mesh = section.getSectionMesh();
            var data = mesh instanceof TerrainGeometryAccess access ? access.luminrt$getTerrainGeometry() : null;
            if (data == null || data.sectionNode() != section.getSectionNode()) {
                scene.remove(section); UPLOADED.remove(section);
                continue;
            }
            if (UPLOADED.get(section) == mesh) continue;
            long node = data.sectionNode();
            scene.put(section, data.meshes(), SectionPos.sectionToBlockCoord(SectionPos.x(node)),
                    SectionPos.sectionToBlockCoord(SectionPos.y(node)), SectionPos.sectionToBlockCoord(SectionPos.z(node)), false);
            UPLOADED.put(section, mesh);
        }
    }

    public static void reset(boolean worldChanged) {
        RenderSystem.assertOnRenderThread();
        collectTerrain = captureDynamic = false;
        DYNAMIC_MESHES.clear();
        if (scene != null) scene.close();
        scene = null; owner = null; UPLOADED.clear();
        if (worldChanged) {
            synchronized (REGISTRY_LOCK) { EPOCH.incrementAndGet(); RESIDENT.clear(); DIRTY.clear(); TERRAIN.clearWorld(); }
            camera = Vec3.ZERO;
        } else { DIRTY.clear(); TERRAIN.clearPending(); }
    }
}
