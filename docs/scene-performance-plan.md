# 光追场景性能改造计划

> 状态：Phase 0–2.3 已实现；Phase 2.4 起实现中
> 范围：Lumin-RT 场景加速结构、几何记录、上传与构建调度
> 参考实现：`prime-pt`（cell/BLAS 批量、材质池稳定地址、按完成证明退休、anchor 相对坐标、每帧构建预算）
> 关联文档：[加速结构与绑定](acceleration-structures.md)、[场景几何接口](scene-geometry.md)、[光影包 Ray Query 指南](shaderpack-ray-query-guide.md)

本文只讨论**性能与数据通路**，不包含纹理采集、LabPBR、OMM、LOD 与 ray tracing pipeline/SBT。

---

## 1. 结论摘要

当前实现的主要开销不在 ray query 本身，而在三条“全量重写”路径：

1. **任何几何变化都重写全世界的 UV 表与材质表**。UV 表 32 B/三角形、材质表 64 B/三角形；全局 dirty 布尔导致一次方块编辑就重新序列化并上传上百 MB（`SceneUVData`/`SceneMaterialData`）。
2. **每帧两次 TLAS 构建 + 实例记录全量重写**。实例是相机相对坐标，相机一动就要重写 64 B × 实例数，且 `beginFrame` 与 `endDynamic` 各构建一次。
3. **每次构建单独分配 scratch、单独开命令缓冲、前后各一次 `ALL_COMMANDS` 全内存屏障**。加载区块时会把光栅管线反复捅停。

改造方向：

- 用 **anchor（f64）+ 相对坐标** 取代“相机相对实例”，让相机移动不再触碰实例与 TLAS。
- 用 **一条 112 B/quad 的 packed 记录** 同时作为着色数据与后续 BLAS 输入，删除独立的 UV 表与材质表（几何内存 ~132 B/三角形 → ~56 B/三角形）。
- 记录与目录放进**分段 arena**，只写变化的 range，旧内容通过 `queueForDestroy` 在 GPU 完成后退休，杜绝原地覆盖。
- 构建与上传**批处理**，scratch 复用，屏障收窄，并按每帧预算轮转。

---

## 2. 目标与非目标

### 2.1 目标

| 指标 | 目标 |
| --- | --- |
| 稳态帧（无编辑、相机移动） | 0 次 BLAS/TLAS 构建、0 次场景数据上传 |
| 相机平移（anchor 邻域内） | 0 次 TLAS 构建、0 次实例上传 |
| 单方块编辑 | 1 次 BLAS 构建 + ≤1 段记录写（KB 级）+ 16 B 目录更新 |
| 动态帧 | 1 次 refit + 只上传变化的记录段 |
| 几何内存 | ~40 B/三角形（单份数据，无重复顶点/UV/材质表） |
| 加载/飞行 | 单帧构建量受预算约束，无长时间卡顿 |

### 2.2 非目标

- 纹理身份、图集/精灵、LabPBR、alpha 纹理与 cutout 正确性（单独立项）。
- OMM、BLAS compaction、LOD、RT pipeline/SBT。
- 修改 Iris/RenderPearl 依赖 JAR。

---

## 3. 现状瓶颈与代码位置

| # | 现象 | 根因 | 位置 |
| --- | --- | --- | --- |
| B1 | 编辑方块后主线程长时间卡顿 | 全局 `uvDirty`/`materialDirty`，整表重新序列化 + 上传 | `SceneAccelerationStructure.java` 的 `put`/`build`；`SceneUVData.java`；`SceneMaterialData.java` |
| B2 | 相机移动时每帧构建 TLAS | 实例是相机相对坐标，`build` 在相机变化时重写全部 64 B 实例 | `SceneAccelerationStructure.java` 的 `build` |
| B3 | 每帧两次 TLAS | `beginFrame` 与 `endDynamic` 各调用一次 `build` | `WorldAccelerationStructures.java` |
| B4 | 构建/上传停顿 | 每次 `record()` 新建 scratch、新建命令缓冲、两个 `ALL_COMMANDS` 屏障 | `VulkanAccelerationStructureBackend.java` 的 `record`/`barrier` |
| B5 | 同几何复制三份 | BLAS 顶点缓冲 + UV 表 + 材质表；索引每个 section 重新生成 | `SceneAccelerationStructure.MeshResource`；`TriangleMesh.sequentialIndices` |
| B6 | 主线程解码与数组爆量 | `uploadResults` 内逐顶点解码；`TriangleMesh` 构造与访问器 clone 数组 | `SodiumRenderRegionManagerMixin.java`；`TriangleMesh.java` |
| B7 | 加载/飞行尖峰 | `flushTerrain` 一帧吃完全部 dirty，无预算 | `WorldAccelerationStructures.flushTerrain` |

### 3.1 量级估算

以 5000 个 section、平均 400 三角形（约 200 万三角形）为例：

- UV 表：2,000,000 × 32 B ≈ **64 MB**
- 材质表（含顶点位置）：2,000,000 × 64 B ≈ **128 MB**
- 一次编辑重写合计 ≈ **192 MB**（CPU 序列化 + staging 上传）
- 相机移动每帧实例上传：5000 × 64 B × 2 次 ≈ **640 KB/帧**，外加 2 次 TLAS 构建

---

## 4. 目标架构

```text
┌───────────────────────────── GPU 侧 ─────────────────────────────┐
│ TLAS（实例 = section，transform = sectionOrigin - anchor）        │
│   └─ BLAS（每 section 一个，按 pass 分组 geometry）               │
│        ├─ vertexData ──┐                                         │
│        └─ indexData ───┼─→ 共享索引 pattern 缓冲（全局一份）      │
│                        │                                         │
│ luminrt_SceneGeometry（单个 arena，同时是 BLAS 输入与 SSBO）      │
│   header | instance dir | geometry dir | QuadRecord[]            │
└──────────────────────────────────────────────────────────────────┘
        ▲ 变化 range 上传                    ▲ 只读解包
┌───────┴────────────── CPU 侧 ─────────────┴──────────────────────┐
│ 源解码（Sodium builder / 动态 Draw）→ QuadRecord 打包             │
│ 分段 arena 分配 + 目录双缓冲                                      │
│ 每帧预算：BLAS 构建队列、记录字节预算、TLAS 合并构建              │
└──────────────────────────────────────────────────────────────────┘
```

关键点：**记录只存一份**，既喂硬件三角形求交，也供 shader 用 `instance/geometry/primitive` 解包；不再有独立的 UV/材质表。

---

## 5. 分阶段计划

### Phase 0：基线测量（0.5 天）

**改动**

- `SceneAccelerationStructure.Statistics` 增加计数器：
  `recordBytesWritten`、`directoryBytesWritten`、`instanceBytes`、`blasBuilds`、`blasUpdates`、`tlasBuilds`、`tlasUpdates`、`scratchAllocations`、`arenaBytes`、`retiredRanges`。
- `WorldAccelerationStructures.statistics()` 透出上述计数（现有接口不变）。
- `LuminRTConfig` 增加 `profileScene` 调试开关，每 120 帧输出一行诊断日志。

**测试场景**：静止 / 走路 / 飞行 / 挖放 / 新区加载 / 100 与 1000 个动态实体。

**验收**：得到改造前基线；后续每个 Phase 用同一场景对照。

---

### Phase 1：稳定 anchor + 每帧一次 TLAS（1–2 天）

**改动**

1. `SceneAccelerationStructure`
   - 新增 `double anchorX/anchorY/anchorZ`、`boolean anchorValid`、`double rebaseDistance`（默认 256）。
   - 实例平移由 `sectionOrigin - camera` 改为 `sectionOrigin - anchor`。
   - `build(camera)` 拆成：
     ```java
     if (!anchorValid || maxAbs(camera - anchor) > rebaseDistance) {
         rebaseAnchor(camera);      // 取整到 64 格，减少抖动
         instancesDirty = true;
         anchorGeneration++;
     }
     if (instancesDirty) uploadInstances();
     if (tlasDirty) buildOrUpdateTlas();
     ```
   - 实例 direct buffer 复用（grow-only），删除每帧 `memCalloc/memFree`。
2. `WorldAccelerationStructures`
   - `beginFrame` 只做 `flushTerrain`，不再 `build`；`current()` 永远返回上一帧已发布的 TLAS。
   - 每帧唯一的 `build` 放在 `endDynamic`。
3. 示例 shader：射线起点改为 `cameraPos - anchor`，命中世界坐标 `anchor + t * direction`。
4. 文档：补充 anchor 语义；`anchorGeneration` 变化时，光影包内所有世界空间缓存/历史必须失效。

**验收**

- 相机平移 100 格：`tlasBuilds` 与 `instanceBytes` 不增长。
- 越过 rebase 距离：恰好 1 次实例上传 + 1 次 TLAS 重建。
- `begin/setup` 程序仍能拿到有效 TLAS（上一帧快照）。

---

### Phase 2：统一几何记录 + 分段 arena（核心，3–5 天）

> 实现状态：2.1–2.3 已完成。实际记录为 **112 B**（UV 用 f32 以支持 tiling 与图集精度），
> 目录与记录拆成两个 SSBO；Phase 2.4（记录直接作为 BLAS 输入）仍待实现。

#### 2.1 QuadRecord 布局（已实现）

每条 **112 B**（7 个 `uvec4`），16 B 对齐：

```text
struct QuadRecord {
    float  p0x, p0y, p0z, pad;   //   0
    float  p1x, p1y, p1z, pad;   //  16
    float  p2x, p2y, p2z, pad;   //  32
    float  p3x, p3y, p3z, pad;   //  48
    float2 uv0; float2 uv1;      //  64
    float2 uv2; float2 uv3;      //  80
    uint   tint;                 //  96：RGBA8 顶点色
    uint   material;             // 100：materialId 低 16 位，bit16 opaque
    uint   pad0, pad1;           // 104
};
```

- 位置保持 **section 局部**（−8..24），实例 transform 继续承担平移。
- UV 使用 f32：UNORM16 无法表示 `> 1` 的平铺 UV，且图集边缘需要高于 f16 的精度。
- 固定三角化 `(0,1,2)`、`(2,3,0)`；quad 用 `primitive >> 1` 定位记录、`primitive & 1` 选半面；三角形直接以 `primitive` 索引记录并复用末角。
- 几何内存约 112 B/quad（56 B/三角形），相对改造前约 264 B/quad（132 B/三角形）降低约 2.4 倍。

#### 2.2 arena 布局

新增 `scene/SceneGeometryArena.java`，底层 buffer 由 `AccelerationStructureBackend.createInputBuffer` 创建（usage 已包含 `BUILD_INPUT | STORAGE`，`shaderSlice()` 可直接绑定为 SSBO）。

```text
offset 0                : header 32 B
  uvec4[0] = { version, instanceCount, geometryCount, anchorGeneration }
  uvec4[1] = { anchor.x bits, anchor.y bits, anchor.z bits, reserved }
offset 32               : instance dir  I × 16 B  { geometryBase, geometryCount, 0, 0 }
offset 32 + 16 * I      : geometry dir  G × 16 B  { recordByteOffset, quadCount, flags, version }
offset 32 + 16 * (I+G)  : QuadRecord[]（16 B 对齐）
```

- 一个 section 一个 instance；每个 pass 一个 geometry（solid / cutout / translucent 保持现状，后续可合并为 solid / non-solid 两个）。
- `flags`：bit0 面透明、bit1 cutout、bit2 动态、bit3 具备 alpha 纹理（预留）。
- `recordByteOffset` 用字节而非记录下标，避免 shader 端乘 80 的额外换算。

#### 2.3 range 生命周期：只新建，不原地覆盖

- 内容变化 → 从 arena 分配**新 range**并写入；旧 range 与旧 BLAS 一起退休。
- 在 `VulkanAccelerationStructure` 增加 `Runnable onDestroy`（或 `AccelerationStructure.onDestroy(Runnable)`），把 `arena.free(range)` 挂到现有 `queueDestruction` 的 `queueForDestroy` 回调里，与 BLAS 一起在 GPU 完成后释放。
- 动态数据每帧新 range + 退休旧 range；free-list 只在完成回调后复用，实际形成 2–3 帧的环形缓冲，内存有界且无读写竞争。
- arena 扩容按 2 倍增长，设上限（建议 256 MiB）；超限时报错并降级为整页重建。

#### 2.4 BLAS 输入

- `AccelerationGeometry.Triangles` 已支持 `vertexStride`：传 `vertexOffset = recordByteOffset`、`vertexStride = 80`、`vertexCount = 4 * quadCount`。
- 索引改为**全局共享 pattern 缓冲**（grow-only）：内容为 `0,1,2, 2,3,0, 4,5,6, 6,7,4, ...`；所有 BLAS 的顶点都从 0 开始，pattern 完全复用。
- 每个 geometry 只通过 build range 的 `primitiveCount = 2 * quadCount` 控制实际范围，不再为每个 section 生成/上传索引。

#### 2.5 目录发布

- 目录仅 16 B/geometry：改一个 section 只写它那 16 B，并翻转 header 的 `version`。
- 跨帧读写竞争用**双缓冲目录**（A/B 两个小 buffer，绑定当前，旧的进完成队列），不要原地改。
- 实例数或 anchor 变化时同样走“新 buffer + 队列退休”。

#### 2.6 删除项

- 删除 `SceneUVData`、`SceneMaterialData`（含未提交的 WIP）。
- `TriangleMesh` 退化为测试辅助或由 `QuadPacker` 取代。

**验收**

- 单方块编辑：`recordBytesWritten ≈ quadCount × 80`（几十 KB 级）、目录 16 B、1 次 BLAS 构建。
- 几何总量 ≈ 40 B/三角形（改造前约 132 B/三角形）。
- 原有 `SceneAccelerationStructureTest` 的 UV/材质断言改为读取新目录。

---

### Phase 3：shader 解包 API（1–2 天）

新增 `docs/examples/ray-query/shaders/lib/luminrt_scene_geometry.glsl`，块名 `luminrt_SceneGeometry`（与现有自动绑定机制一致）：

```glsl
layout(std430) readonly buffer luminrt_SceneGeometry {
    uvec4 luminrt_geometry[];
};

uvec4 header = luminrt_geometry[0];              // version, I, G, anchorGeneration
vec3  anchor = uintBitsToFloat(luminrt_geometry[1].xyz);
uvec4 inst   = luminrt_geometry[2 + instanceIndex];
uvec4 geo    = luminrt_geometry[2 + header.y + inst.x + geometryIndex];
// 记录地址 = geo.x 字节；每条 112 B，7 × uvec4
```

提供的函数（命名与旧 API 对齐，便于迁移）：

- `luminrtGetHitUV(instance, geometry, primitive, bary, out uv)`
- `luminrtGetHitMaterial(instance, geometry, primitive, out materialId, out alphaMode, out flags)`
- `luminrtGetHitTint(instance, geometry, primitive, out tint)`
- `luminrtGetAnchor(out vec3)` / `luminrtGetAnchorGeneration()`

删除 `luminrt_SceneUV` 与 `luminrt_SceneSurface`，同步更新文档、示例与 smoke 测试；版本号从 1 升到 2（0.1.0 允许 breaking，需在 README/release note 标注）。

**验收**

- `PipelineRayQuerySmokeTest` 使用新 SSBO 完成“UV 替换 → GPU 读回”验证。
- 与旧解码逐三角形比对固定夹具，UV 误差 ≤ 1/65535。

---

### Phase 4：批处理、scratch 复用、每帧预算（2–3 天）

**RHI**

- `AccelerationStructureBackend` 增加 `buildBatch(List<Build>)`：一帧一个 encoder，一次 `vkCmdBuildAccelerationStructuresKHR` 传多个 build info。
- `ScratchPool`：grow-only，按本帧最大 scratch 需求共用一块。
- 屏障收窄为 `ACCELERATION_STRUCTURE_BUILD` ↔ `RAY_TRACING/COMPUTE/GRAPHICS`，access 使用 `ACCELERATION_STRUCTURE_READ/WRITE`，替换现有 `ALL_COMMANDS + MEMORY_READ|WRITE`。
- 上传与构建合并进同一 encoder，中间只保留一次必要依赖。

**调度**

- `WorldAccelerationStructures` 增加每帧预算：
  - `maxSectionUpdatesPerFrame`（默认 32，可配置 8–128）；
  - `maxRecordBytesPerFrame`（默认 4 MiB）；
  - 未完成的 dirty 保留到下一帧，round-robin 避免饿死；
  - 动态数据始终处理（自身有界）。
- TLAS：实例数不变用 `update`，变化才 `build`；预算未完成时保持旧 BLAS 与旧目录，不发布半成品。

**验收**：加载/飞行时单帧 CPU 构建时间与提交数封顶；静止帧所有计数器为 0。

---

### Phase 5：把打包移出渲染线程（2–3 天）

**现状**：`SodiumRenderRegionManagerMixin` 在渲染线程 `uploadResults` 中逐顶点解码并分配数组；`TriangleMesh` 构造与访问器再 clone 一遍。

**改动**

- 地形：在 Sodium builder 线程（`ChunkBuildOutput` 完成时）直接把顶点打包为 `QuadRecord` 字节；主线程只做 range 分配、上传与 BLAS 构建。
- 动态：`StagedVertexBuffer.Draw.append` 只登记“draw → 源 MeshData 引用”，帧内统一打包进动态 arena；draw 数量与拓扑不变时复用上一帧记录。
- 引入不可变 `PackedSection` / `PackedDraw` 快照，渲染线程只读。
- 取消 `TriangleMesh` 的 clone 语义，或直接删除该类。

**验收**：JFR 中渲染线程无大数组分配；单帧解码段 < 1 ms。

---

### Phase 6：清理与验证（1–2 天）

- 删除废弃类与绑定：`SceneUVData`、`SceneMaterialData`，以及 `ProgramResourcesMixin`/`VulkanProgramResourcesMixin` 中的旧名称。
- 文档：更新 `acceleration-structures.md`；`scene-uv.md` 重命名为 `scene-geometry.md`；同步示例 shader。
- 测试清单：
  - arena 分配/释放/碎片/扩容；
  - 目录布局、双缓冲与版本校验；
  - 记录打包 → shader 解码往返；
  - anchor rebase 与 `anchorGeneration`；
  - 预算轮转公平性与稳定帧零工作；
  - 构建失败不发布半成品（回滚）。

---

## 6. 任务拆分与排期

| # | 任务 | 主要文件 | 预估 | 依赖 |
| --- | --- | --- | --- | --- |
| 1 | 基线计数器与场景矩阵 | `SceneAccelerationStructure`、`LuminRTConfig` | 0.5d | — |
| 2 | anchor + 单次 TLAS | `SceneAccelerationStructure`、`WorldAccelerationStructures`、示例 GLSL | 1.5d | 1 |
| 3 | arena + range 完成证明退休 | 新增 `SceneGeometryArena`、`AccelerationStructure.onDestroy` | 2d | 2 |
| 4 | 记录打包、共享索引、BLAS 输入 | 新增 `QuadPacker`、`SodiumTerrainMesh`、`SceneAccelerationStructure` | 2d | 3 |
| 5 | 目录双缓冲与增量发布 | 新增 `SceneGeometryDirectory`、`SceneAccelerationStructure` | 1.5d | 4 |
| 6 | shader 解包 helper 与 smoke | `luminrt_scene_geometry.glsl`、`PipelineRayQuerySmokeTest` | 1.5d | 5 |
| 7 | 批处理、scratch 池、屏障收窄 | `VulkanAccelerationStructureBackend`、RHI | 2d | 4 |
| 8 | 每帧预算与轮转 | `WorldAccelerationStructures` | 1d | 7 |
| 9 | 线程化打包 | Sodium mixin、动态采集 | 2.5d | 4 |
| 10 | 清理、文档、回归 | 全局 | 1.5d | 全部 |

总计约 **2 周**（单人）。

**最小止血路径**：只做 1 → 2 → 7 → 8（约 5 天）。不做记录重构也能消除大部分稳态帧与加载尖峰开销；内存与带宽收益需等 3–6。

---

## 7. 风险与对策

| 风险 | 对策 |
| --- | --- |
| 覆盖仍被 shader 读取的记录 | 只新建 range；旧 range 在 `queueForDestroy` 回调中释放，绝不原地改 |
| arena 碎片 | best-fit free-list + 2 倍扩容 + 上限；超限时整页重建 |
| 目录跨帧读写竞争 | 目录双缓冲 + 按帧绑定，旧目录进完成队列 |
| UV 精度/平铺 | 使用 f32，UNORM16 无法表示平铺 UV 与图集边缘 |
| anchor 变化导致光影包历史失效 | header 暴露 `anchorGeneration`，文档要求检测后清历史 |
| `vertexStride = 80` 与对齐要求 | 记录 16 B 对齐，位置在 offset 0；`vertexData` 满足 4 B 对齐 |
| 旧光影包不兼容（SSBO 改名） | 版本号升 2，README/release note 明示 breaking，示例同步 |
| 动态每帧新 range 的分配开销 | free-list 仅在完成回调后复用，实际 2–3 帧环形，稳态分配接近 0 |
| cutout 仍不正确 | 记录预留 `alphaMode/flags`；alpha 纹理身份在纹理采集立项后接入，OMM 放后续 |
| 压力测试不足 | Phase 0 的场景矩阵贯穿每个阶段；未达标的阶段不合并 |

---

## 8. 验收清单

每个 Phase 合并前至少满足：

- [ ] 场景矩阵中稳定帧：`blasBuilds`、`tlasBuilds`、`recordBytesWritten`、`instanceBytes` 增量为 0。
- [ ] 相机平移（anchor 内）：`tlasBuilds == 0`、`instanceBytes == 0`。
- [ ] 单方块编辑：`recordBytesWritten <= 单 section 记录字节`、`tlasBuilds <= 1`。
- [ ] 动态帧：`blasUpdates <= 1`、`recordBytesWritten` 与变化顶点量同阶。
- [ ] 加载新区：单帧构建数量不超过预算。
- [ ] 现有单元测试全绿；`-PasSmokeTest runClient` 的 AS/UV smoke 通过（含 Vulkan 验证层无错误）。
- [ ] 文档与示例 shader 与实现一致。

---

## 9. 建议的提交切分

1. `scene: add profiling counters`（Phase 0）
2. `scene: anchor-relative instances and single TLAS build per frame`（Phase 1）
3. `rhi: batch builds, scratch pool, narrow barriers`（Phase 4 前半）
4. `scene: geometry arena with completion-backed range retirement`（Phase 2.2/2.3）
5. `scene: quad records and shared index buffer`（Phase 2.1/2.4）
6. `scene: incremental directory publication`（Phase 2.5/Phase 4 后半）
7. `shader: luminrt_SceneGeometry unpack helpers`（Phase 3）
8. `scene: worker-thread packing`（Phase 5）
9. `docs: scene geometry contract`（Phase 6）

每一步都可独立构建、独立回滚；前半程即可在真实帧中量到收益。

---

## 10. 附录：与 prime-pt 的对应关系

| Lumin-RT 改造项 | prime-pt 对应机制 |
| --- | --- |
| 112 B QuadRecord + `primitive>>1` 解包 | 176/240/272/432 B quad 记录 + `primitiveIndex >> 1` 与半面位 |
| 共享索引 pattern | 固定 `012 / 230` 共享索引 |
| 分段 arena + 完成证明退休 | 材质池按格式分页、设备地址不迁移、按最后消费者与 GPU 完成退休 |
| anchor + f32 相对坐标 | `scene.anchor` 与 `absolutePosition = anchor + relativePosition` |
| 每帧构建预算与轮转 | 每帧 1–128 个 4³ section 格、公平轮转 |
| 目录版本号 | 规范 texture descriptor 与 scene revision/完成水位 |
| 记录内预留 alpha 标志 | OMM 与 coverage 支持（后续） |

不移植的部分：Rust 场景编译器、压缩源缓存、材质/灯页、Rust worker 池、BLAS compaction 与批量 scratch（可作为后续 P2 项评估）。
