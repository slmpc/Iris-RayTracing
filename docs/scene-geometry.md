# 场景几何接口

Vulkan AS 本身只保存几何。LuminRT 在 TLAS 之外发布两张自动绑定的只读 SSBO，与 `luminrt_Scene` 的实例、geometry、primitive 顺序严格对应：

| 块名 | 内容 |
| --- | --- |
| `luminrt_SceneGeometry` | 目录：header、anchor、实例索引、geometry 索引 |
| `luminrt_SceneRecords` | 每 quad/三角形一条 112 B 记录（位置、UV、tint、材质） |

块名是保留名称，由 LuminRT 自动分配 binding，不需要 `bufferObject`，也不要在 `shaders.properties` 中声明。不要向这两个缓冲区写入，也不要把块名用于其他数据或块数组。

## 接入

```glsl
#ifdef ENABLED_LUMIN_RAY_TRACING_EXTENSION
#extension GL_EXT_ray_query : require
#include "/lib/luminrt_ray_query.glsl"
#include "/lib/luminrt_scene_geometry.glsl"
#endif
```

完成 `luminrtTraceClosest` 后读取命中数据：

```glsl
vec2 uv;
bool hasUV = hit.found && luminrtGetHitUV(hit.instanceIndex, hit.geometryIndex,
    hit.primitiveIndex, hit.barycentrics, uv);

uint material, tint;
bool hasMaterial = hit.found && luminrtGetHitMaterial(hit.instanceIndex, hit.geometryIndex,
    hit.primitiveIndex, material, tint);

vec3 localPosition;
bool hasPosition = hit.found && luminrtGetHitPosition(hit.instanceIndex, hit.geometryIndex,
    hit.primitiveIndex, hit.barycentrics, localPosition);
```

`localPosition` 是 anchor 相对坐标；世界坐标为 `anchor + localPosition`。位置与 UV 来自同一条记录，可用于重建法线或估计纹理足迹。

`instanceIndex` 必须来自 `rayQueryGetIntersectionInstanceCustomIndexEXT`，不是 `rayQueryGetIntersectionInstanceIdEXT`。对 committed 三角形使用 `true` 的 getter，对候选三角形使用 `false`；调用前先检查 intersection type。helper 对版本不符、负索引、越界范围返回 `false`，并把输出设为零。

## 目录格式（版本 1）

块内容为 `layout(std430) readonly buffer luminrt_SceneGeometry { uvec4 luminrt_geometry[]; };`，偏移以 16 字节记录计。

| 记录 | x | y | z | w |
| --- | --- | --- | --- | --- |
| 0：header | 版本 1 | anchor 代数 | 实例数 I | geometry 总数 G |
| 1：anchor | anchor.x | anchor.y | anchor.z | 0 |
| 2 到 2+I-1：实例 | geometry 基址 | geometry 数 | 0 | 0 |
| 2+I 到 2+I+G-1：geometry | 着色块字节偏移 | 记录数 | flags | 位置块字节偏移 |

- flags 的 bit0 表示四边形拓扑，bit1 表示 opaque，bit2 表示动态实例的 geometry。
  着色端可用 bit2 跳过静态辐照度缓存等只能由静态表面更新的数据。
- 实例的 `geometry 基址` 是 geometry 目录内的下标（从 0 开始），不是全局记录号。
- 空场景仍提供有效的 32 字节 header 与 anchor 记录。

## 记录格式（每条 112 B：位置块 64 B + 着色块 48 B）

位置块同时是 BLAS 的顶点输入（每个角 16 B，步长 16）：

| 字 | 内容 |
| --- | --- |
| 0 | p0.xyz + padding |
| 1 | p1.xyz + padding |
| 2 | p2.xyz + padding |
| 3 | p3.xyz + padding |

着色块（每条记录 3 个 uvec4）：

| 字 | 内容 |
| --- | --- |
| 0 | uv0.x uv0.y uv1.x uv1.y |
| 1 | uv2.x uv2.y uv3.x uv3.y |
| 2 | tint（RGBA8）、material（低 16 位方块/实体身份，bit16 opaque）、0、0 |

四边形每两个三角形一条记录，固定 `0-1-2`、`2-3-0` 三角化；独立三角形复用末角作为第四角。UV 插值：

- 四边形：`primitive & 1` 选择半面，重心权重作用在对应三个角上；
- 三角形：`primitive` 直接索引记录，使用角 0/1/2。

材质身份是解码后的方块/实体 ID：地形从 Iris XHFP 的 `mc_Entity` 编码 `(blockId + 1) << 1 | renderTypeBit` 还原；
动态网格使用其 `mc_Entity.x`。bit16 为 opaque 标志。

`primitiveIndex` 与 BLAS 的固定索引 pattern 对应，不要用 Minecraft 原始顶点缓冲的索引。

## anchor 与射线起点

场景使用 anchor 相对坐标：实例平移为 `worldPosition - anchor`，anchor 固定在 64 格整数网格上，相机在 256 格半径内移动不会重写实例或 TLAS。

```glsl
vec3 origin = luminrtSceneOrigin(cameraPositionInt, cameraPositionFract);
```

整数部分先相减再叠加小数，远坐标下仍精确；仅在相机坐标可精确表示为 float 时才使用单参数重载。命中世界坐标为 `anchor + hit.position`。

`anchorGeneration` 变化时，所有锚定世界坐标的历史、辐照度和时序重投影缓存必须失效。

## 更新与生命周期

- 记录按范围增量维护：编辑或动态内容变化只重写该 geometry 的记录范围，不重建整表。
- 记录缓冲同时是 BLAS 的顶点输入（步长 112 B）；索引使用一张全局共享的 `0-1-2` / `2-3-0` pattern，不逐网格生成。
- 目录在场景变化时重建；记录范围在引用它的旧目录完成 GPU 使用后才释放。
- 记录缓冲可能扩容搬移；不要跨帧缓存记录偏移、设备地址或目录指针，每次从当前绑定的 SSBO 读取。
- 目录与记录都可能在帧间替换；shader 只应使用当帧绑定。

## UV 的含义和限制

- 地形 UV 来自 Sodium `a_TexCoord` 的 15 位解码，不包含最高位的偏移方向与 mip 收缩修正；与应用 mip 收缩的光栅采样坐标可能存在微小差异，也不是编码前原始浮点 UV。
- 动态 Minecraft 网格按实际顶点 `UV0` 的 float 读取；无 UV 时记录为零。
- UV 不标识纹理：地形通常是方块图集，实体可能使用独立纹理；当前表不暴露每命中的纹理、材质类别或 alpha 规则。不能把所有 UV 都用于同一图集。
- 当前没有 alpha 阈值、法线或 shader 顶点位移；cutout 几何被标记为 opaque，命中阶段不能剔除透明区域。若调用者另有纹理与 alpha 规则，可在候选命中阶段自行确认，但不要假定 `luminrt_SceneRecords` 已提供纹理身份。
- 四边形与 BLAS 共用固定三角化；不要对 barycentrics 做屏幕空间透视修正。

## 验证

单元测试覆盖记录打包、目录布局、anchor 重定位、增量记录范围与缓冲换代；Vulkan smoke 使用真实 Iris `ProgramResources` 与 compute ray query 检查自动绑定和 UV 替换。

```powershell
.\gradlew.bat test build
.\gradlew.bat -PasSmokeTest runClient
```
