# Iris VK 加速结构与 Pipeline 绑定

## 分析结果

本次分析对象是项目指定的 `iris-vk-1.11.6-snapshot+mc26.3-local.jar`。
Vineflower 1.12.0 反编译结果在 `references/iris-vk`；当前项目运行
`gradlew genSources` 生成的 Minecraft 26.3 源码在 `references/minecraft-26.3`。
整个 `references/` 已加入 `.gitignore`，不参与编译和发布。
Iris 的 `VulkanSamplerHeap` 有一处方法反编译失败；本次所用后端和命令桥源码已生成。

- `net.irisshaders.iris.backend.RenderBackend` 是 Iris 的后端接口；
  `VulkanRenderBackend` 和 `GlRenderBackend` 分别实现 Vulkan 和 OpenGL。
- `VulkanCommandBridge` 获取 RenderPearl 的 `VulkanDevice`，访问同一图形队列的
  `VulkanCommandEncoder`，并拒绝在 render pass 内录制原生命令。
- `VulkanStorageBuffer` 使用 VMA 分配，但原有 usage 不包含设备地址和 AS build input，
  因而本次新增专用缓冲区，不把原有存储缓冲区直接当作构建输入。
- `VulkanSynchronization` 跟踪现有纹理和存储缓冲区；它未覆盖 AS 资源。
  本次在上传和构建前后添加保守的 synchronization2 全内存屏障，并把 TLAS 的读取加入 pipeline 的资源访问列表。
- RenderPearl 在 `VulkanBackend.createDevice` 创建逻辑设备，在 `createVma` 创建分配器。
  AS 扩展、buffer device address、可用时的 ray query 特性及 VMA 地址分配标志在这里启用。

## 接口

```java
AccelerationStructureBackend backend =
    AccelerationStructureBackendProvider.get(Iris.getRenderBackend());
if (backend == null || !backend.isSupported()) {
    return;
}

try (AccelerationStructureBuffer vertices = backend.createInputBuffer(36)) {
    // 上传 direct ByteBuffer；这里应写入三个 float3 顶点。
    vertices.upload(0, vertexData);
    var triangles = new AccelerationGeometry.Triangles(
        vertices, 0, 3, 12, null, 0,
        AccelerationGeometry.IndexType.NONE, 1, true);
    var description = new AccelerationStructureDescription(
        AccelerationStructure.Type.BOTTOM_LEVEL, List.of(triangles), true, true);
    try (AccelerationStructure blas = backend.create(description)) {
        backend.build(blas);
        // 修改并上传同一布局的数据后，可进行原地更新。
        backend.update(blas);
        long address = blas.deviceAddress();
    }
}
```

支持三角形 BLAS（非索引、uint16 或 uint32 索引）、AABB BLAS、实例 TLAS。
创建时查询存储和 scratch 大小；构建时分配并对齐 scratch 设备地址。
每个结构固定几何布局和数量，`update` 必须先 `build`，且创建时开启 `allowUpdate`。
更新时不能改变索引内容、primitive 的 active/inactive 状态及其他 Vulkan 禁止改变的数据。
调用方负责保证索引有效，AABB 的最小值不大于最大值，以及实例引用已构建的 BLAS。

TLAS 输入为连续的 64 字节 `VkAccelerationStructureInstanceKHR` 记录，变换为行优先 3×4 矩阵，
实例引用写入 BLAS 的设备地址。BLAS 必须在 TLAS 之前构建，并保持存活至 TLAS 的最后一次使用完成。
需要绑定 descriptor 时可使用 `VulkanAccelerationStructure.vkHandle()`。

所有操作在 render thread 上执行；上传、构建、更新必须在 render pass 外。
输入缓冲区在后续 build/update 前保持开放。调用方按 TLAS → BLAS → 输入缓冲区顺序关闭资源。
`close()` 使用 Minecraft 的延迟销毁队列，不立即释放仍被 GPU 使用的对象；已录制的 descriptor 快照也持有 TLAS 引用。
最后一个快照退休后才会排队销毁已关闭的 TLAS；后端关闭时也会清理其未关闭资源。
此实现仅使用图形队列，不提供跨队列所有权转移。

OpenGL 的 `accelerationStructures()` 返回 `null`。Vulkan 不支持所需扩展或特性时，
不添加这些设备要求，`isSupported()` 返回 `false`；尝试创建或构建会抛出 `UnsupportedOperationException`。
可用时启用 `VK_KHR_ray_query` 和 `rayQuery`，`supportsRayQuery()` 单独报告该能力。
尚未添加 ray tracing pipeline、SBT 或 `traceRays`。世界几何收集与场景管理见下节。

## 世界几何的生成与更新

开启 LuminRT 拓展后，LuminRT 自动维护场景 TLAS。光影包声明以下标量资源即可使用，
无需手动注册；其他名称的 AS 仍遵循后面的显式注册接口。

```glsl
#version 460
#extension GL_EXT_ray_query : require
uniform accelerationStructureEXT luminrt_Scene;
```

场景使用**相机相对世界坐标**，射线方向为世界轴方向，不含 view rotation。
从相机发射的射线起点为 `vec3(0.0)`；世界位置先减去本帧相机位置。
实例平移先在 CPU 用 double 相减，再转为 float，避免直接把远距离世界坐标写入 BLAS。

- 地形：Iris Vulkan 使用 Sodium 0.9.2，实际编译路径绕过原版 `SectionCompiler`。
  在 `ChunkBuildBuffers.createMesh` / `createModifiedTranslucentMesh` 保存当次格式，
  在 `RenderRegionManager.uploadResults` 正式上传后、结果销毁前复制各层网格，
  解码 `a_Position` 的 20 位区段局部坐标，支持 Sodium Compact 与 Iris XHFP 扩展步长。
  不发布取消、过期或未被上传的构建结果；纯 `ChunkSortOutput` 不替换快照。
  原版采集入口仍在 `SectionCompiler.compile` 复制 `MeshData`，并随 `CompiledSectionMesh` 发布。
  每个区段一个 BLAS，solid/cutout/translucent 各为独立 geometry；未变化的区段不重新上传或构建。
  区段重编译替换 BLAS；透明排序仅修改光栅索引，不触发 AS 重建。
  Sodium 的 `RenderSection.delete` 删除旧快照与实例；世界重置清空注册。
  开启拓展时主动请求区段重编译，即使 Iris 的格式未变化，也能补齐此前已加载的区段。
- 动态顶点：仅在主世界 `FeatureRenderDispatcher.prepareFrame` 期间采集
  `StagedVertexBuffer.Draw.append` 的真实 `MeshData`，包括走此路径的实体、方块实体、移动方块及粒子。
  采集后、创建主渲染通道前构建本帧动态 BLAS 和 TLAS。
  动态批次的 geometry 数量、顶点数量、索引及 opaque 属性完全一致时 refit；否则重建。
  当前将这些批次合入一个动态实例，不提供逐实体的稳定身份。
- TLAS：相机平移或动态 refit、实例数量不变时原地 update；地形/BLAS 引用变化时 build。
  实例数量变化时替换 TLAS 和实例输入缓冲区。空场景使用真正的零实例 TLAS。
  相机与几何均未变化时跳过上传和构建。帧起始的 begin/setup 使用已发布地形与上一帧动态数据，
  本帧动态数据在 feature 准备完成后可供 shadow/prepare/gbuffers/deferred/composite 使用。
- 生命周期：TLAS 显式持有引用的 BLAS。替换时，旧 BLAS 租约在已录制 GPU 工作完成后释放；
  descriptor 快照持有旧 TLAS 时，其 BLAS 仍保持有效。重载 pipeline 释放 GPU 场景，
  当前区段的 CPU 快照供后续重建；世界重置清空区段注册并使旧工作线程发布失效。

实现参考 `prime-pt` 的静态 BLAS 保留、结构变化与姿态变化分离，以及依 GPU 完成回收资源的思路；
没有移植其 Rust 场景编译器、压缩、材质系统或完成槽上传机制。
所有 AS 命令沿用 LuminRT 图形队列的同步与延迟销毁，不增加 CPU fence 等待或独立队列。

Sodium 地形支持 `RG32_UINT` 压缩位置的 quads；原版/动态路径支持位置属性为 `RGB32_FLOAT` 的 quads、triangles、triangle strip 和 triangle fan。
四边形采用原版的 `0,1,2 / 2,3,0` 三角化；线、点及其他位置编码跳过。
仅 solid 地形标记 opaque，cutout、translucent 和动态几何保留 ray query candidate。
**当前收集几何位置和 UV，UV 通过自动绑定的只读 `luminrt_SceneUV` SSBO 提供**，使用方式见 [场景 UV 接口](scene-uv.md)。尚未提供纹理标识、材质表或 alpha test；候选命中是否确认由 shader 决定，
`gl_RayFlagsOpaqueEXT` 会把叶片透明区域等也视为几何遮挡。
源模型的面剔除仍沿用原版编译输出，shader 顶点位移尚未反映到 AS。
天空、云、天气、世界边界、第一人称手持物及绕过上述 CPU 顶点路径的自定义 GPU 绘制未接入。
动态采集可能包含同一路径的贴图覆盖层，不声明逐材质或物理表面的去重。

每次动态准备会复制位置与索引；动态 BLAS 稳定拓扑时只上传位置。
相机移动时目前仍全量重写 TLAS 实例，输入上传及逐 BLAS 构建沿用保守屏障，
未实现 prime-pt 的批量 scratch、局部实例上传、BLAS compaction 或构建预算。
可通过 `WorldAccelerationStructures.statistics()` 查看驻留网格及 BLAS/TLAS build/update 次数；
通过 `WorldAccelerationStructures.terrainSectionCount()` 查看 Sodium 地形快照数量（不含动态实例）。
未做实际游戏帧率或显存测量，不声称达到 prime-pt 的性能。

新增无窗口测试覆盖三角化绕序、源内存释放后的快照有效性、动态 refit、拓扑变化重建、
大坐标相机相对变换、静态 BLAS 复用、空场景、删除及分配/构建失败后的重试。
2026-10-11 已运行 Vulkan GPU smoke，默认与 Iris 扩展格式的实际合并网格、源码内存销毁后的快照，
以及场景空 TLAS、refit、替换和删除路径均通过。
该 smoke 不加载世界；真实区段采集、卸载及 Nsight 全场景结果仍需在世界中验收。

手动验证时使用 Vulkan 启动并开启拓展，加载声明 `luminrt_Scene` 的光影包，
检查放置/破坏方块、区段卸载与复用、实体移动、远坐标移动、切换维度、关闭拓展及重载光影。
带 Vulkan 验证层的独立场景 smoke 命令仍为 `.\gradlew.bat -PasSmokeTest runClient`。

## 传入 Iris Pipeline

着色器声明标量或固定长度的一维数组：

```glsl
#version 460
#extension GL_EXT_ray_query : require
uniform accelerationStructureEXT scene;
// 着色器中的数组声明：uniform accelerationStructureEXT scenes[2];
```

LuminRT 扩展 `VulkanResourceTransformer`，在普通 uniform lowering 前识别 AS，
随后统一分配 set 0 中未被其他资源占用的 binding。原始 `layout(set=..., binding=...)`
会重新分配，调用方按资源名称注册，不依赖原始 binding 数字。
跨着色器阶段的同名 AS 必须具有相同的标量/数组形态和数量。

创建 `ProgramResources` 后、调用 `finish()` 和创建 pipeline 前注册 TLAS：

```java
var backend = AccelerationStructureBackendProvider.get(Iris.getRenderBackend());
if (backend == null || !backend.supportsRayQuery()) {
    return;
}

var discovered = VulkanShaderPatcher.discover(sources);
var resources = new ProgramResources(packSamplers, discovered.layout());
AccelerationStructureBindings.of(resources)
    .setAccelerationStructure("scene", () -> currentTlas);
// 数组使用 setAccelerationStructures("scenes", () -> List.of(tlasA, tlasB))。
// 仍按 Iris 原有接口注册 shader 中的 SSBO、image、sampler 和 uniform。
resources.finish();
var patched = VulkanShaderPatcher.patch(sources, resources.getBindingPlan());
var uniforms = new BufferUniformHolder(patched.layout());
var compute = Iris.getRenderBackend()
    .createComputePipeline("example", patched, resources, uniforms);
```

Supplier 每次解析资源快照时读取，支持在帧间替换 TLAS。缺少注册或数组数量不符会报错。
绑定时检查 TLAS 已构建、尚未关闭且与 pipeline 同属一个 Vulkan 设备，拒绝把 BLAS 直接绑定到 shader。
`ProgramResources.Bindings` 的扩展字段保存该次解析结果，descriptor 与同步路径共享这份结果。
使用 graphics pipeline 时，仍按 Iris 的流程在创建 render pass 前执行
`backend.createSync().read(pipeline).nextIs(SyncCommand.Stage.GRAPHICS).run()`。
构建/更新与 shader 访问共用图形队列；AS 的完整构建屏障及 pipeline 读取访问覆盖 build → ray query 的依赖。

`VulkanResourceSets` 创建 `VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR` 的 layout 和 pool，
通过 `VkWriteDescriptorSetAccelerationStructureKHR` 写入 TLAS handle，支持 descriptor 数组。
快照缓存键包含所有 TLAS handle，替换资源时不会误用旧 descriptor。
Graphics shader、compute dispatch 及使用 `VulkanProgramResources.getImageViews()` 的原生命令桥共用这条路径。
不要绕过该方法直接把 TLAS handle 塞进普通 image map；快照需要资源对象以校验和维护生命周期。

实现通过 `ShaderResourceLayout.Kind.IMAGE` 加专用 `type` 标记表示 AS，以兼容依赖 JAR 的既有 enum。
标记在注册阶段与普通 image 分离，在 Vulkan descriptor 阶段转换为真正的 AS 类型；它不占用 storage image descriptor。
无需修改或重新打包本地 Iris JAR。没有 ray query 特性的 Vulkan 设备会拒绝创建包含 AS 绑定的 pipeline。
OpenGL 后端继续返回 `null`，不支持这些 AS shader 声明。
非一致数组索引等其他可选 descriptor indexing 特性未启用；本次实机测试使用常量索引。

## 验证

```powershell
.\gradlew.bat test build
.\gradlew.bat -PasSmokeTest runClient
.\gradlew.bat -PasSmokeTest -PsmokeBackend=OPENGL runClient
```

集成测试模组只在传入 `-PasSmokeTest` 时加载，不进入最终 JAR。
Vulkan 测试需要支持 AS 的 GPU 及已安装的 Vulkan 验证层；它测试三角形 BLAS 和 TLAS 的构建/更新、
AABB BLAS 构建、错误更新和已关闭资源的拒绝、GPU fence 完成与延迟销毁。
还通过真实 Iris compute/graphics pipeline 执行 ray query 并读回 GPU 结果：
TLAS 数组中命中与未命中、交换数组元素、相同绑定的缓存复用、fragment shader 查询、
错误数量/名称、缺少注册、BLAS/未构建/已关闭 TLAS 的拒绝，以及 TLAS 在 shader 工作录制后、提交前关闭的生命周期。
OpenGL 测试检查后端返回 `null`。成功后写入 `build/smoke-run-*/as-smoke-passed.txt` 并退出游戏。
检查当次日志中的 `LUMINRT_AS_SMOKE_PASSED` 与 Vulkan validation 消息，不只依赖标记文件。

集成测试还验证 Sodium 的拓展开关注册、应用与配置持久化，以及实际 Mixin 注入的宏在 GLSL 预处理中的启用和关闭分支。
这部分通过后日志输出 `LUMINRT_SETTINGS_SMOKE_PASSED`；测试在游戏资源加载完成后执行，以便使用 Sodium 的完整设置。

另有 26 项单元测试，覆盖配置持久化、几何与 AS 声明约束、场景资源更新、Sodium 压缩位置及 UV 解码、UV 表布局与动态更新、区段更新合并与卸载重放。UV GPU smoke 还验证自动绑定、重心插值、表替换、缓存复用和空场景。
已在 NVIDIA GeForce RTX 4060 Laptop GPU 上开启 Vulkan 验证层验证上述 GPU 路径，未发现 Vulkan 验证错误；
OpenGL 空实现也已通过。无 AS 支持设备的路径尚未实机验证。

## 光影包迁移

shader 侧的完整适配、材质数据契约、SBT/递归替代方案和多次反弹说明见 [光影包与 ray query 指南](shaderpack-ray-query-guide.md)。
