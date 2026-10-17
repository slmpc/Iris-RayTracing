package com.github.slmpc.luminrt.scene;

import java.util.List;

/** 绑定编译时区段坐标，避免区段对象循环复用后把旧结果放入新位置。 */
public record TerrainGeometry(long sectionNode, List<TriangleMesh> meshes) {
    public TerrainGeometry { meshes = List.copyOf(meshes); }

    public boolean sameContent(TerrainGeometry other) {
        if (sectionNode != other.sectionNode || meshes.size() != other.meshes.size()) return false;
        for (int i = 0; i < meshes.size(); i++) if (!meshes.get(i).sameContent(other.meshes.get(i))) return false;
        return true;
    }
}
