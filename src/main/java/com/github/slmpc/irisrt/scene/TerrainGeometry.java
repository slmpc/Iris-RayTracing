package com.github.slmpc.irisrt.scene;

import java.util.List;

/** 绑定编译时区段坐标，避免区段对象循环复用后把旧结果放入新位置。 */
public record TerrainGeometry(long sectionNode, List<TriangleMesh> meshes) {
    public TerrainGeometry { meshes = List.copyOf(meshes); }
}
