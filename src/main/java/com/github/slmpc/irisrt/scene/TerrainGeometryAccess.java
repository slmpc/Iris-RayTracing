package com.github.slmpc.irisrt.scene;

/** 区段编译结果和已发布网格共享不可变的 AS 输入快照。 */
public interface TerrainGeometryAccess {
    TerrainGeometry irisrt$getTerrainGeometry();
    void irisrt$setTerrainGeometry(TerrainGeometry geometry);
}
