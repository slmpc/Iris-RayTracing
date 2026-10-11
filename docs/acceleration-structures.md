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
尚未添加 ray tracing pipeline、SBT、`traceRays` 或世界几何收集。

## 传入 Iris Pipeline

着色器声明标量或固定长度的一维数组：

```glsl
#version 460
#extension GL_EXT_ray_query : require
uniform accelerationStructureEXT scene;
// 着色器中的数组声明：uniform accelerationStructureEXT scenes[2];
```

IrisRT 扩展 `VulkanResourceTransformer`，在普通 uniform lowering 前识别 AS，
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
检查当次日志中的 `IRISRT_AS_SMOKE_PASSED` 与 Vulkan validation 消息，不只依赖标记文件。

另有 8 项单元测试，覆盖几何数据约束及 AS 声明的阶段合并、binding 分配和冲突处理。
已在 NVIDIA GeForce RTX 4060 Laptop GPU 上开启 Vulkan 验证层验证上述 GPU 路径，未发现 Vulkan 验证错误；
OpenGL 空实现也已通过。无 AS 支持设备的路径尚未实机验证。
