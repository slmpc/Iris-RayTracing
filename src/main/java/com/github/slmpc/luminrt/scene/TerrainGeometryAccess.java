package com.github.slmpc.luminrt.scene;

/** 区段编译结果和已发布网格共享不可变的 AS 输入快照。 */
public interface TerrainGeometryAccess {
    TerrainGeometry luminrt$getTerrainGeometry();
    void luminrt$setTerrainGeometry(TerrainGeometry geometry);
}
