# Iris VK RHI 加速结构

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
  本次在上传和构建前后添加保守的 synchronization2 全内存屏障。
- RenderPearl 在 `VulkanBackend.createDevice` 创建逻辑设备，在 `createVma` 创建分配器。
  AS 扩展、buffer device address 特性和 VMA 地址分配标志需要在这里启用。

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
`close()` 使用 Minecraft 的延迟销毁队列，不立即释放仍被 GPU 使用的对象；后端关闭时也会清理其未关闭资源。
此实现仅使用图形队列，不提供跨队列所有权转移。

OpenGL 的 `accelerationStructures()` 返回 `null`。Vulkan 不支持所需扩展或特性时，
不添加这些设备要求，`isSupported()` 返回 `false`；尝试创建或构建会抛出 `UnsupportedOperationException`。
不启用 ray query、ray tracing pipeline，也不修改 shaderpack、着色器绑定或世界几何收集。

## 验证

```powershell
.\gradlew.bat test build
.\gradlew.bat -PasSmokeTest runClient
.\gradlew.bat -PasSmokeTest -PsmokeBackend=OPENGL runClient
```

集成测试模组只在传入 `-PasSmokeTest` 时加载，不进入最终 JAR。
Vulkan 测试需要支持 AS 的 GPU 及已安装的 Vulkan 验证层；它测试三角形 BLAS 和 TLAS 的构建/更新、
AABB BLAS 构建、错误更新和已关闭资源的拒绝、GPU fence 完成与延迟销毁。
OpenGL 测试检查后端返回 `null`。成功后写入 `build/smoke-run-*/as-smoke-passed.txt` 并退出游戏。
检查当次日志中的 `IRISRT_AS_SMOKE_PASSED` 与 Vulkan validation 消息，不只依赖标记文件。

已在 NVIDIA GeForce RTX 4060 Laptop GPU 上开启 Vulkan 验证层验证上述路径，未发现 Vulkan 验证错误；
OpenGL 空实现也已通过。无 AS 支持设备的路径尚未实机验证。
