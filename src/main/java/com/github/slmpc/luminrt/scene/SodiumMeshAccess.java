package com.github.slmpc.luminrt.scene;

import com.mojang.renderpearl.api.vertex.VertexFormat;

/** 保存生成该网格时的实际格式，并允许在 Sodium 构建线程上预先解码几何。 */
public interface SodiumMeshAccess {
    /** 修改后的透明网格键；正常 pass 直接使用 `TerrainRenderPass` 实例。 */
    Object MODIFIED_TRANSLUCENT = new Object();

    VertexFormat luminrt$getVertexFormat();
    void luminrt$setVertexFormat(VertexFormat format);

    /** 在构建线程写入自持几何快照；同一键只保留最新结果。 */
    void luminrt$pack(Object key, TriangleMesh mesh);

    /** 在渲染线程取走快照；未打包时返回 null，由调用方回退解码。 */
    TriangleMesh luminrt$takePacked(Object key);
}
