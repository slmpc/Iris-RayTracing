package com.github.slmpc.luminrt.smoke;

import com.github.slmpc.luminrt.LuminRT;
import com.github.slmpc.luminrt.scene.SodiumMeshAccess;
import com.github.slmpc.luminrt.scene.SodiumTerrainMesh;
import com.github.slmpc.luminrt.scene.TriangleMesh;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;

/** 在实际 Mixin 环境验证默认与 Iris 扩展格式的区段合并网格。 */
final class SodiumTerrainSmokeTest {
    static void run() {
        // 主动加载上传与卸载目标，让无世界 smoke 也检查 Mixin 注入签名。
        verifyInjection("net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager", "luminrt$publish");
        verifyInjection("net.caffeinemc.mods.sodium.client.render.chunk.RenderSection", "luminrt$unload");
        verify(ChunkMeshFormats.COMPACT);
        verify(FormatAnalyzer.createFormat(true, true, true, true));
        LuminRT.LOGGER.info("LUMINRT_SODIUM_TERRAIN_SMOKE_PASSED");
    }

    private static void verifyInjection(String target, String handler) {
        try {
            var methods = Class.forName(target).getDeclaredMethods();
            if (java.util.Arrays.stream(methods).noneMatch(method -> method.getName().contains(handler)))
                throw new AssertionError("Missing terrain Mixin handler: " + handler);
        } catch (ClassNotFoundException failure) { throw new AssertionError(failure); }
    }

    private static void verify(ChunkVertexType type) {
        var buffers = new ChunkBuildBuffers(type);
        try {
            buffers.init(new BuiltSectionInfo.Builder(), 0);
            var vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
            for (int i = 0; i < 4; i++) {
                vertices[i].x = i == 1 || i == 2 ? 2 : 0;
                vertices[i].y = i >= 2 ? 2 : 0;
                vertices[i].z = 1;
                vertices[i].ao = 1;
                vertices[i].u = vertices[i].x;
                vertices[i].v = vertices[i].y;
            }
            buffers.get(DefaultTerrainRenderPasses.SOLID).getVertexBuffer(ModelQuadFacing.UNASSIGNED).push(vertices, 0);
            var built = buffers.createMesh(DefaultTerrainRenderPasses.SOLID, 0, false, true);
            TriangleMesh copied;
            try {
                var actual = ((SodiumMeshAccess)built).luminrt$getVertexFormat();
                if (actual != type.getVertexFormat()) throw new AssertionError("Sodium mesh must retain its build-time format");
                copied = SodiumTerrainMesh.copy(built.getVertexData().getDirectBuffer(), actual, true);
            } finally { built.getVertexData().free(); }
            if (copied.triangleCount() != 2 || copied.positions()[2] != 1)
                throw new AssertionError("Merged terrain snapshot must survive source destruction");
        } finally { buffers.destroy(); }
    }
}
