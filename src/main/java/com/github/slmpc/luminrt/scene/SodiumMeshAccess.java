package com.github.slmpc.luminrt.scene;

import com.mojang.renderpearl.api.vertex.VertexFormat;

/** 保存生成该网格时的实际格式，避免光影重载后用新格式解码旧任务。 */
public interface SodiumMeshAccess {
    VertexFormat luminrt$getVertexFormat();
    void luminrt$setVertexFormat(VertexFormat format);
}
