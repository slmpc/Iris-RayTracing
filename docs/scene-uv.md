# 场景 UV 接口

Vulkan AS 本身仅保存几何。LuminRT 同时采集地形 `a_TexCoord` 和动态网格 `UV0`，将 UV 发布到只读 SSBO `luminrt_SceneUV`，与 `luminrt_Scene` 的实例、geometry 和 primitive 顺序对应。

## 光影包接入

在拓展开关宏内启用 `GL_EXT_ray_query` 并引入 [luminrt_scene_uv.glsl](examples/ray-query/shaders/lib/luminrt_scene_uv.glsl)。该 helper 声明保留的 storage **块名** `luminrt_SceneUV`；无需分配 `bufferObject`，也无需指定 binding。Iris 会重新分配 Vulkan binding。不要将该块名用于其他数据或声明块数组，不要向该缓冲区写入。

```glsl
#ifdef ENABLED_LUMIN_RAY_TRACING_EXTENSION
#extension GL_EXT_ray_query : require
#include "/lib/luminrt_ray_query.glsl"
#include "/lib/luminrt_scene_uv.glsl"
#endif
```

完成 `luminrtTraceClosest` 后读取 UV：

```glsl
vec2 uv;
bool hasUV = hit.found && luminrtGetHitUV(hit.instanceIndex,
    hit.geometryIndex, hit.primitiveIndex, hit.barycentrics, uv);
```

`instanceIndex` 必须是 `rayQueryGetIntersectionInstanceCustomIndexEXT`，不是 `rayQueryGetIntersectionInstanceIdEXT`。对 committed 三角形，三个索引和 barycentrics 的 getter 使用 `true`；对候选三角形使用 `false`。必须先检查对应 intersection type。helper 对不存在的 UV、负索引、越界索引和未知数据版本返回 `false`，并将 `uv` 设为零。

三角形顶点 UV 按 BLAS 索引排列，插值为 `uv0*(1-b.x-b.y) + uv1*b.x + uv2*b.y`。四边形与 BLAS 共用 `0-1-2`、`2-3-0` 三角化。不要对 barycentrics 做屏幕空间透视修正；ray query 已给出三维三角形上的权重。

## 数据格式（版本 1）

块内容为 `layout(std430) readonly buffer luminrt_SceneUV { uvec4 luminrt_uvRecords[]; };`。偏移均以 16 字节记录计，浮点值以 IEEE float 位模式存储，使用 `uintBitsToFloat` 解码。

| 记录 | x | y | z | w |
| --- | --- | --- | --- | --- |
| 0：头部 | 版本 1 | 实例数 I | geometry 总数 G | 三角形总数 T |
| 1 到 I：实例 | geometry 首记录 | geometry 数 | 0 | 0 |
| 1+I 到 I+G：geometry | 三角形首记录 | 三角形数 | 有 UV 为 1，否则 0 | 0 |
| 每个三角形第一条 | uv0.x 位模式 | uv0.y 位模式 | uv1.x 位模式 | uv1.y 位模式 |
| 每个三角形第二条 | uv2.x 位模式 | uv2.y 位模式 | 0 | 0 |

总大小 `16*(1+I+G+2*T)` 字节。没有 UV 的 geometry 仍占用三角形记录，其 UV 为零且标记为 0。空场景提供有效的 16 字节头部。该表不提供永久实体 ID；不要跨帧缓存 custom index。

场景增删、静态几何替换或动态 UV 改变时上传新表，在同一渲染线程构建周期与 TLAS 一起发布。仅相机变换或动态位置改变时复用 UV 表。UV 改变不影响 BLAS 拓扑匹配，动态网格仍可 refit。旧表由命令编码器等待 GPU 完成后销毁；shader 存储读取也进入 Iris 的资源同步流程。CPU 快照持有 UV，并保留当前 geometry 用于表生成。

## UV 的含义和限制

- Sodium 0.9.2 / Iris XHFP 使用 `(packed & 32767)/32768` 解码，对应 `_vert_tex_diffuse_coord`。编码包含一单位量化偏移；最高位是偏移方向。当前接口不返回方向或 `u_TexCoordShrink` 修正，因此与应用 mip 收缩的光栅采样坐标可能存在微小差异，也不是编码前原始浮点 UV。
- 动态 Minecraft 网格按实际顶点 stride/offset 读取 `UV0` 的两个 float；无该属性时显式标记缺失。
- UV 不标识纹理。地形通常使用方块图集，实体可能使用独立纹理；当前表不暴露每个命中的纹理、材质或地形/实体分类。不能把所有 UV 都用于同一方块图集。
- 当前没有 alpha 阈值、材质表、法线或 shader 顶点位移。UV 支持不等于完整 cutout/透明材质支持。若调用者另有准确的纹理与 alpha 规则，可在候选命中阶段重建 UV 并决定是否 `rayQueryConfirmIntersectionEXT`；不要启用 `gl_RayFlagsOpaqueEXT` 跳过需要 alpha 判断的候选。

在普通 ray tracing pipeline 的逻辑映射中，该 helper 相当于 closest-hit/any-hit 中读取顶点属性并插值的步骤：closest-hit 对 committed 命中调用，any-hit 对 candidate 调用。完整 payload、miss 和多跳逻辑见 [ray query 指南](shaderpack-ray-query-guide.md)。

## 验证

单元测试覆盖真实 Sodium 编码器 UV 解码、动态 MeshData 的非零属性偏移、源内存释放、快照所有权、表布局、多 geometry/instance、缺失 UV、动态 UV 更新及失败后的重试。Vulkan smoke 使用真实 Iris ProgramResources 和 compute ray query，检查自动绑定、重心插值、UV 替换、描述符缓存和空场景。

```powershell
.\gradlew.bat test build
.\gradlew.bat -PasSmokeTest runClient
```

诊断示例显示 UV 的 RG 颜色，无 UV 时显示重心坐标。真实世界的图集边缘、实体纹理和 alpha 处理仍需光影包独立验证。
