# LuminRT 光影包适配与 Ray Query 实现指南

本文面向已经了解 Iris/OptiFine 光影包目录结构、GLSL 和 Vulkan 光线追踪概念的光影作者。
LuminRT 是 Iris Vulkan 的光线追踪拓展，目前仅支持 RayQuery；不提供独立的 Ray Tracing Pipeline。
目标是说明 LuminRT 当前能提供什么、如何声明场景 TLAS，以及如何在普通 `gbuffer`、`composite` 或 `compute` shader 中用 `GL_EXT_ray_query` 写出与 ray tracing pipeline 相同的查询逻辑。

本文依据本项目当前的 Iris Vulkan 1.11.6、Minecraft 26.3 参考源码和 `GL_EXT_ray_query` 规范编写。`references/` 仅是开发参考，光影包不需要携带这些文件。

## 1. 能力、前提和开关

LuminRT 只在 Vulkan 后端尝试创建加速结构。设备需要支持：

- `VK_KHR_acceleration_structure`；
- buffer device address；
- `VK_KHR_ray_query` 和 ray query feature（使用 shader 查询时需要）。

OpenGL 后端没有 AS 资源。设备不支持时，光影包应保留软件路径；不要只判断宏就假定硬件查询一定可用。

LuminRT 设置中的 **LuminRT → 设置** 控制拓展宏。开启时 Iris 标准宏环境加入：

```glsl
ENABLED_LUMIN_RAY_TRACING_EXTENSION
```

该宏表示用户开启了功能；它不是 GLSL 预定义的 Vulkan 能力探测。推荐使用宏包住 `#extension`、AS 声明和查询代码，同时保留关闭路径：

```glsl
#ifdef ENABLED_LUMIN_RAY_TRACING_EXTENSION
#extension GL_EXT_ray_query : require
#endif
```

如果设备不支持 ray query，Vulkan pipeline 创建或资源绑定会失败；光影包不能在 shader 内捕获这个错误。因此发布光影包时应把 ray query 当作可选渲染路径，并提供不含 AS 声明的备用 shader/pipeline。

## 2. 场景资源声明和坐标

自动场景资源的名称是 `luminrt_Scene`，类型为标量 `accelerationStructureEXT`：

```glsl
#ifdef ENABLED_LUMIN_RAY_TRACING_EXTENSION
#extension GL_EXT_ray_query : require
uniform accelerationStructureEXT luminrt_Scene;
#endif
```

LuminRT 在 shader 资源转换阶段识别该声明，并为它分配 set 0 中的空闲 binding。不要指定或依赖原始 `layout(binding=...)` 数字；不同阶段同名声明必须保持完全相同的标量/数组形态。

当前自动资源还支持普通固定长度一维数组，但 `luminrt_Scene` 自动绑定只提供一个 TLAS（标量或长度为 1 的数组）。数组资源需要宿主显式通过 `AccelerationStructureBindings.setAccelerationStructures` 注册，普通光影包不能调用这个 Java API。

场景采用 anchor 相对坐标：

- TLAS 实例变换中的平移是 `worldPosition - anchor`；
- anchor 固定在 64 格整数网格上，相机在其 256 格半径内移动不会重写实例或 TLAS；
- 射线起点用 `luminrtSceneOrigin(cameraPositionInt, cameraPositionFract)`（整数部分先相减再叠加小数，远坐标下仍精确）；只有相机坐标可精确表示为 float 时才用单参数重载；
- 射线方向和命中位置都在 anchor 相对空间中，命中世界坐标为 `anchor + hit.position`；
- `gbufferModelViewInverse` 用于把视空间方向转换到世界方向；
- 不要再把 `cameraPosition` 直接加到 ray origin；helper 已扣除 anchor。

`luminrt_SceneHeader` 提供 `anchor` 与 `anchorGeneration`；代数变化时，任何锚定世界坐标的缓存（历史、辐照度、时序重投影）必须失效。CPU 端先用 double 做相减再转 float，减少远坐标精度损失。Iris 的 `cameraPosition` 会对远距离坐标进行重定位，不能直接与未经同样重定位的绝对世界坐标混用。需要绝对位置时，应核对 `cameraPositionInt` / `cameraPositionFract` 的约定；ray query 本身不需要绝对位置。

## 3. 当前几何内容和缺失内容

当前 Iris Vulkan 使用 Sodium 0.9.2 的地形路径，绕过原版 `SectionCompiler`。LuminRT 在 `ChunkBuildBuffers` 生成合并网格时保存实际顶点格式，在 `RenderRegionManager.uploadResults` 正式上传后、临时内存销毁前复制并解码 `a_Position` 的 20 位压缩位置；兼容默认 Compact 与 Iris XHFP 的不同步长。每个区段的 solid、cutout、translucent 几何进入同一区段 BLAS 的不同 geometry。区段重编译替换 BLAS，删除区段移除实例；纯透明排序输出不重建 AS。原版 `SectionCompiler` 路径作为另一个采集入口保留。

动态几何在 `FeatureRenderDispatcher.prepareFrame` 的 `StagedVertexBuffer.Draw.append` 路径复制真实 `MeshData`，覆盖经过该 staged 路径的实体、方块实体、移动方块和粒子。动态 geometry 的顶点数、索引和 opaque 标记不变时使用 BLAS update；拓扑变化时重建。

Sodium 地形使用 `a_Position` 为 `RG32_UINT` 的压缩 quads；原版/动态顶点采集接受 `Position` 为 `RGB32_FLOAT` 的 quads、triangles、triangle strip 和 triangle fan。四边形固定三角化为 `0,1,2` 与 `2,3,0`。AS 中没有：

- 法线、切线、顶点颜色和光照值；UV 已通过独立只读 SSBO 提供，见 [场景 UV 接口](scene-uv.md)；
- block/entity/material ID 的 shader 可读表；
- alpha test、纹理采样或透明材质判定；
- shader 中的顶点位移结果；
- 天空、云、天气、世界边界、第一人称手持物和绕过 staged CPU 顶点路径的自定义 GPU 几何。

当前 AS 命中表示几何三角形命中，结合 `luminrt_SceneUV` 可重建 UV，但仍不能把 cutout 当成已经执行 alpha test 的透明表面。`gl_RayFlagsOpaqueEXT` 会使 traversal 把候选按 opaque 方式处理；它不是 Minecraft 材质 alpha 语义的实现。

## 4. 从 ray tracing pipeline 映射到 ray query

普通 Vulkan ray tracing pipeline 通常有 ray generation、intersection、any-hit、closest-hit、miss 和 callable shader，并通过 SBT 选择每个实例的记录。LuminRT 当前没有 `VK_KHR_ray_tracing_pipeline`、SBT 或 `traceRayEXT` 路径；ray query 把 traversal 嵌入已有 graphics/compute shader，由调用 shader 自己完成这些职责。

对应关系如下：

| 普通 pipeline 逻辑 | Ray query 写法 |
| --- | --- |
| ray generation | 在 fragment/compute invocation 中计算 origin、direction、tMin、tMax 并初始化 `rayQueryEXT` |
| `traceRay` | `rayQueryInitializeEXT` 后循环 `rayQueryProceedEXT` |
| intersection shader | 当前三角形 BLAS 由硬件固定求交；AABB 需要在 candidate AABB 分支中自行计算并 `rayQueryGenerateIntersectionEXT` |
| any-hit | 每次 `rayQueryProceedEXT` 返回后读取 candidate；透明候选不确认就继续，确认后等价于提交候选 |
| closest-hit | traversal 完成后读取 committed intersection，计算命中材质、法线、BRDF 和光照 |
| miss | committed type 为 `gl_RayQueryCommittedIntersectionNoneEXT` |
| ray flags | `rayQueryInitializeEXT` 的 `rayFlags` 和 `cullMask` |
| SBT instance record | `instanceCustomIndex`、`instanceShaderBindingTableRecordOffset`、`geometryIndex` 和 `primitiveIndex`；当前自动场景 custom index 是实例顺序，shader binding offset 为 0；实例 flags 为 1（关闭三角形朝向剔除） |
| payload | 普通局部变量、函数返回值或 SSBO/image 写入；ray query 没有硬件 payload |
| callable shader | 普通 GLSL 函数，或按材质 ID 分支/查表 |

ray query 的 traversal 循环必须在同一个 invocation 内完成。它不会启动另一个 shader stage，也不会自动保存 payload、递归栈或反弹状态。

## 5. 基本 API 和正确的 closest-hit 查询

`rayQueryInitializeEXT` 参数顺序为：AS、ray flags、8-bit cull mask、origin、tMin、direction、tMax。origin/direction 必须是有限值；`tMin`、`tMax` 必须非负且 `tMin <= tMax`。

```glsl
rayQueryEXT q;
rayQueryInitializeEXT(q, luminrt_Scene,
    gl_RayFlagsNoneEXT, 255u,
    origin, 0.001, direction, maxDistance);

while (rayQueryProceedEXT(q)) {
    uint candidate = rayQueryGetIntersectionTypeEXT(q, false);
    if (candidate == gl_RayQueryCandidateIntersectionTriangleEXT) {
        // 这里是 any-hit 位置。当前没有材质表，直接确认几何候选。
        rayQueryConfirmIntersectionEXT(q);
    }
}

uint committed = rayQueryGetIntersectionTypeEXT(q, true);
if (committed == gl_RayQueryCommittedIntersectionTriangleEXT) {
    float t = rayQueryGetIntersectionTEXT(q, true);
    vec2 bary = rayQueryGetIntersectionBarycentricsEXT(q, true);
    int instance = rayQueryGetIntersectionInstanceCustomIndexEXT(q, true);
    int geometry = rayQueryGetIntersectionGeometryIndexEXT(q, true);
    int primitive = rayQueryGetIntersectionPrimitiveIndexEXT(q, true);
    bool front = rayQueryGetIntersectionFrontFaceEXT(q, true);
    vec3 hitPosition = origin + t * direction;
}
```

`committed` 参数必须是编译期常量 `true` 或 `false`。candidate 查询发生在 traversal 循环中；committed 可在已有合法提交结果时读取；为了得到最终最近交点，本指南在 traversal 完成后读取。candidate getter 只能用于 `proceed` 返回 true 的有效候选，不能在循环结束后继续读取候选。先判断类型：None 不能读取命中 T；重心坐标和 front face 只对三角形有效，AABB candidate 不能读取三角形的 T/重心坐标。candidate Triangle/AABB 的值是 0/1，committed None/Triangle/Generated 的值是 0/1/2，不要混用整数。当前场景只有三角形 geometry，AABB 分支可视为未支持。

### any-hit、透明候选和 shadow ray

如果未来提供可读材质 ID，any-hit 等价逻辑是：candidate 命中后取得 instance/geometry/primitive，执行 alpha test；通过 alpha test 才 `rayQueryConfirmIntersectionEXT`，否则什么都不做，让 traversal 继续。

当前没有该材质接口，所以不能正确实现 cutout。不要用固定的 UV、随机 alpha 或距离阈值伪造材质透明度。

只需要遮挡结果时，使用 `gl_RayFlagsTerminateOnFirstHitEXT`：

```glsl
rayQueryEXT shadow;
rayQueryInitializeEXT(shadow, luminrt_Scene,
    gl_RayFlagsOpaqueEXT | gl_RayFlagsTerminateOnFirstHitEXT,
    255u, shadowOrigin, 0.001, lightDirection, lightDistance - 0.002);
while (rayQueryProceedEXT(shadow)) {}
bool blocked = rayQueryGetIntersectionTypeEXT(shadow, true)
    != gl_RayQueryCommittedIntersectionNoneEXT;
```

自遮挡需要沿法线偏移 origin。不要把 `tMin` 设为极大值来掩盖错误法线；这会丢失近处真实几何。

## 6. AABB、自定义 intersection 和 ray flags

`rayQueryGenerateIntersectionEXT` 只能在 candidate AABB 存在时生成/提交自定义交点。使用 AABB 时，循环应检查 `gl_RayQueryCandidateIntersectionAABBEXT`，通过 `rayQueryGetIntersectionCandidateAABBOpaqueEXT` 和实例/primitive 数据执行自定义求交，再调用 `rayQueryGenerateIntersectionEXT(q, tHit)`。

三角形 candidate 使用 `rayQueryConfirmIntersectionEXT`。二者不可混用，也不能为没有 candidate 的 query 生成交点。

常用 flags：

- `gl_RayFlagsOpaqueEXT`：按 opaque candidate 处理；
- `gl_RayFlagsNoOpaqueEXT`：强制 non-opaque 语义，让三角形经过应用确认；它不是过滤器。`CullOpaqueEXT` / `CullNoOpaqueEXT` 才按 opaque 分类剔除；
- `gl_RayFlagsTerminateOnFirstHitEXT`：首个可提交命中后结束；
- `gl_RayFlagsCullBackFacingTrianglesEXT` / `CullFrontFacingTrianglesEXT`：三角形面剔除。

opaque/no-opaque/cull 相关互斥组合不能同时设置；front/back cull 也不能同时设置。`cullMask` 会与 TLAS instance mask 按位与，结果为零的实例不可见。当前自动场景实例 mask 为 `0xff`，所以普通 query 使用 `255u`。

## 7. 在 Iris pass 中生成 ray origin 和 direction

Iris 的 composite 顶点是全屏四边形，常用 fragment shader 输入是 `texcoord`。Iris 提供 `gbufferProjectionInverse`、`gbufferModelViewInverse`、`viewWidth`、`viewHeight`、`near`、`far` 等 uniform；这些来自当前 Vulkan/Iris pipeline，而不是 ray tracing pipeline 的内建变量。

透视相机的简化生成方式：

```glsl
vec2 ndc = texcoord * 2.0 - 1.0;
vec4 viewPoint = gbufferProjectionInverse * vec4(ndc, -1.0, 1.0);
vec3 viewDirection = normalize(viewPoint.xyz / viewPoint.w);
vec3 worldDirection = normalize(mat3(gbufferModelViewInverse) * viewDirection);
vec3 origin = luminrtSceneOrigin(cameraPositionInt, cameraPositionFract); // anchor 相对；需包含 ray_query helper
```

此版本 Iris 会把 shaderpack-facing 投影恢复为传统 NDC `[-1,1]`，并转换普通深度采样，使光影包看到正向深度。使用正常 Iris 路径的 `depthtex` 时，可用 `depth * 2.0 - 1.0` 与 `gbufferProjectionInverse` 重建视空间位置；不要再次执行 reverse-Z。直接读取后端原始纹理或自定义未经转换的资源时，需要另行核对约定。射线方向生成只使用 near-plane 方向，不需要读取 depth；如果从屏幕像素发射第一段 primary ray，命中深度可用于与 raster G-buffer 对照。

在 shadow pass 中，使用 shadow pass 的矩阵和光方向；不要复用主视图的 `gbufferModelViewInverse`。在 compute 中按 `gl_GlobalInvocationID` 和 `viewWidth/viewHeight` 生成 pixel center，并进行 bounds check。

## 8. 用 ray query 实现一次、两次和多次反弹

### 一次 closest-hit

一次查询的核心是“初始化 → proceed 循环 → 确认 candidate → 读取 committed”。命中后使用 `t`、barycentrics、front face 和实例/primitive 索引取得局部结果。当前没有顶点/材质表时，只能做几何可视化、距离 AO、硬阴影或法线实验。

### 两次反弹

ray query 不支持在 query 内递归。第一次命中后，shader 自己计算反射或折射方向，然后初始化第二个 query：

```glsl
LuminRTHit first = luminrtTraceClosest(origin, primaryDirection, 0.001, far);
if (!first.found) return skyRadiance(primaryDirection);

vec3 normal = loadNormalFromGeometryTable(first);
vec3 bounceDirection = normalize(reflect(primaryDirection, normal));
vec3 bounceOrigin = first.position + normal * 0.001;
LuminRTHit second = luminrtTraceClosest(bounceOrigin, bounceDirection, 0.001, far);
vec3 incoming = second.found ? evaluateSecondHit(second) : skyRadiance(bounceDirection);
return evaluateFirstHit(first, incoming);
```

实际 shader 中需要显式保存 `throughput`、`radiance`、当前介质和 bounce index。每次 bounce 都要设置有限的 `tMax`、避免 self-intersection，并对 `throughput` 做 NaN/Inf 清洗。

### 固定上限的循环路径追踪

普通光追 pipeline 可以通过递归或多级 shader 调度表达路径；ray query 必须在一个 fragment/compute invocation 中展开成固定上限循环：

```glsl
vec3 radiance = vec3(0.0);
vec3 throughput = vec3(1.0);
vec3 origin = cameraOrigin;
vec3 direction = primaryDirection;

for (int bounce = 0; bounce < 4; ++bounce) {
    LuminRTHit hit = luminrtTraceClosest(origin, direction, 0.001, far);
    if (!hit.found) { radiance += throughput * skyRadiance(direction); break; }

    Material material = loadMaterialFromGeometryTable(hit);
    radiance += throughput * material.emission;
    // 一般 BSDF 必须按采样概率归一化；delta 材质使用单独的离散权重。
    BsdfSample bs = sampleBsdf(material, -direction, randomState);
    if (bs.pdf <= 0.0) break;
    throughput *= bs.f * abs(dot(material.normal, bs.direction)) / bs.pdf;
    if (max(throughput.r, max(throughput.g, throughput.b)) < 0.001) break;

    vec3 normal = material.normal;
    direction = bs.direction;
    origin = hit.position + normal * 0.001;
}
```

这个示例中的 `Material`、`loadMaterialFromGeometryTable`、`sampleBsdf` 和 `BsdfSample` 是光影包自己的实现，不是 LuminRT 提供的 API。当前 LuminRT 无法由 hit 自动还原 `Material`；屏幕 G-buffer 只能描述第一段可见光栅表面，不能还原离屏或第二次命中；这些命中需要 CPU 发布的几何/材质表或自定义 SSBO，并自行保证 `instance/geometry/primitive` 到材质的映射。

随机采样需要稳定的 pixel/bounce seed；不要用 query traversal 次数作为随机数。需要 Russian roulette 时，在至少一两次 bounce 后按 throughput 计算生存概率，并除以该概率保持估计量无偏。

## 9. 从普通 ray tracing shader 拆分到 Iris pass

建议按以下方式迁移：

1. 把 ray generation 的相机射线生成放到 `composite.fsh` 或 compute shader 的每个 pixel invocation。
2. 把 closest-hit 的交点读取和材质评价放进同一个函数；把 payload 字段改为局部变量或结构体返回值。
3. 把 miss shader 改为 `if (committedType == None) skyRadiance(direction)`。
4. 把 any-hit 改为 candidate 分支；透明 candidate 不确认，opaque candidate 确认。
5. 把 shadow ray 单独写成 `TerminateOnFirstHit` query，不要复用需要最近距离的 query。
6. 把 callable shader 改成普通函数或按材质/实例 ID 分支。
7. 把递归深度改成固定循环；每一轮明确更新 origin、direction、throughput、介质状态和随机状态。
8. 把 ray tracing pipeline 的 SBT record 数据改成 instance custom index、geometry index、primitive index 或显式 SSBO。
9. 在相同 pass 中使用现有 Iris `colortex`/`image`/SSBO 资源写回结果；compute pass 使用 `layout(local_size_*)` 和 `const vec2 workGroupsRender` 或 `const ivec3 workGroups` 配置，composite pass 使用 `/* RENDERTARGETS: ... */`。
10. 保留 raster fallback，并在 fallback 中避免声明 `accelerationStructureEXT`，关闭拓展后备用路径可以编译；宏开启但设备不支持时不会自动降级，需关闭拓展并重新加载。

## 10. Descriptor、数组和阶段规则

AS 声明由 LuminRT 的 shader layout transformer 在普通资源 lowering 前提取。set/binding 会被重新分配到 set 0 的空闲 binding。跨 vertex/fragment/compute 阶段的同名资源必须有相同数量和数组形态；缺少注册、数组长度不符、绑定 BLAS、未构建 TLAS 或已关闭 TLAS 都会在资源解析时抛错。

descriptor 快照按 TLAS handle 缓存。帧间替换 TLAS 后，旧快照不会误绑定新资源；TLAS 和其引用 BLAS 会延迟到 GPU 完成后释放。shader 只应在正常 Iris pipeline 的资源解析路径中使用 AS，不要把 handle 当作普通 image binding。

## 11. 常见错误

- **把 `ENABLED_LUMIN_RAY_TRACING_EXTENSION` 当成设备能力。** 它只是设置宏；仍需提供 fallback。
- **在 ray query 中使用非编译期 `committed`。** 规范要求 `true/false` 为编译期常量。
- **只调用 `rayQueryProceedEXT`，不确认 triangle candidate。** 三角形 candidate 不会自动变成应用想要的 closest hit；需要 `rayQueryConfirmIntersectionEXT`，除非 flags/实现路径已经明确 opaque 语义。
- **把 candidate 当作最终命中。** candidate 可能被更近的几何替换；最终结果读取 committed。
- **把 instance custom index 当成 block ID。** 当前自动场景写入的是实例序号，不是材质或方块 ID。
- **用 `primitiveIndex` 查 Minecraft 原始 vertex buffer。** AS 是独立复制的三角化几何，原版绘制 buffer 可能已释放、排序或复用，且当前没有稳定的 geometry-to-material 表。
- **把 cutout/translucent 当作 alpha-aware。** 当前已提供 UV，但没有纹理标识/材质 alpha 规则。
- **把相机绝对坐标直接写入 ray origin。** 场景是 anchor 相对坐标；用 `luminrtSceneOrigin(cameraPositionInt, cameraPositionFract)` 计算起点。
- **在 render pass 内自行录制 Vulkan AS 命令。** LuminRT 已在 render pass 外完成构建；光影包只通过 shader 资源使用 TLAS。
- **每次 bounce 使用零偏移。** 会造成 self-intersection；使用基于法线和场景尺度的有限 offset，并清洗非有限值。
- **对 Iris 已转换的深度再次 reverse-Z。** 正常 shaderpack 路径使用 Iris 提供的逆矩阵和深度 helper，原始后端纹理另行核对。

## 12. 示例和验证

`docs/examples/ray-query/` 提供一个几何诊断示例：

- `lib/luminrt_ray_query.glsl`：最近命中和首命中遮挡 helper；
- `composite.vsh` / `composite.fsh`：把屏幕像素射线转换到相机相对世界空间，显示 barycentrics；
- 示例关闭宏时继续显示 `colortex0`。

把示例 shader 合入光影包时，应保留目标包自己的 `composite` 资源声明、输出布局和 pass 顺序。示例不是完整可发布光影包，也没有材质 alpha、法线或光照模型。

构建验证（不启动游戏）：

```powershell
.\gradlew.bat test build
.\gradlew.bat compileSmokeJava -PasSmokeTest
```

需要启动窗口和兼容设备的 GPU 验证：

```powershell
.\gradlew.bat -PasSmokeTest runClient
.\gradlew.bat -PasSmokeTest -PsmokeBackend=OPENGL runClient
```

GPU smoke 用于覆盖 BLAS/TLAS 构建、更新、数组 descriptor、compute/fragment ray query、资源替换和延迟销毁。启动实际游戏后还应检查：区段编译/卸载、区段复用、实体移动、相机远距离移动、维度切换、光影重载、拓展关闭以及不支持 ray query 的 Vulkan 设备 fallback。

本文的完整路径追踪示例是逻辑模板，不是对当前几何数据接口的材质完整承诺。UV 已由 `luminrt_SceneUV` 提供；要实现完整 Minecraft 光追材质，仍需要额外发布法线/材质索引与 alpha test 数据，并让它们与 BLAS 的 geometry/primitive 索引保持同一生命周期和拓扑版本。

## 13. 材质适配的数据契约

以下是**后续扩展的设计建议，不是已提供的 LuminRT shader API**。普通 ray tracing pipeline 的 closest-hit 也需要顶点/材质表；改成 ray query 不会自动获得这些数据。

建议宿主同步发布以下表：

| 表 | 查询键 | 记录内容 |
| --- | --- | --- |
| InstanceTable | custom index | 对应 BLAS 的 geometry 起始位置、对象变换、材质覆盖、场景版本 |
| GeometryTable | 实例对应的 geometry 起点 + geometry index | triangle 索引起点、顶点起点、材质 ID、数据版本 |
| TriangleTable | triangle 起点 + primitive index | 三个顶点索引；与 AS 三角化完全一致 |
| VertexTable | 顶点索引 | 位置、UV、法线、颜色、必要时切线 |
| MaterialTable | 材质 ID | 纹理层/图集区域、alpha 阈值、粗糙度、金属度、发光、折射率 |

使用 std430 时明确字段大小和对齐，例如用 `vec4` 保存位置/法线、`uvec4` 保存三角形，避免 Java 紧密 float3 与 GLSL vec3 的步长不一致。纹理使用现有图集或明确支持的 texture array；不要假定设备支持任意 descriptor 非均匀索引。

命中重心坐标 `b = (u,v)` 对应权重 `(1-u-v,u,v)`：

```glsl
vec3 w = vec3(1.0 - b.x - b.y, b.x, b.y);
vec2 uv = v0.uv * w.x + v1.uv * w.y + v2.uv * w.z;
vec3 nsObject = normalize(v0.normal * w.x + v1.normal * w.y + v2.normal * w.z);
vec3 ngObject = normalize(cross(v1.position - v0.position, v2.position - v0.position));
// 非均匀缩放时，法线需要逆转置；不是直接乘方向变换矩阵。
vec3 nsWorld = normalize(transpose(mat3(worldToObject)) * nsObject);
vec3 ngWorld = normalize(transpose(mat3(worldToObject)) * ngObject);
```

baseline `GL_EXT_ray_query` 没有直接返回三角形法线的 getter，不能只凭 T 和 barycentrics 重建法线。当前几何诊断的重心色不依赖该数据。AS 实例已禁用面对剔除，frontFace 只是命中朝向信息；双面材质应按入射方向调整着色法线，并保留几何法线用于偏移和介质判定。

custom index 当前随实例集合变化而变化。不能把上一帧的 custom index 当成永久实体 ID。扩展材质表时必须与同一份 TLAS 发布，至少使用场景 generation：BLAS 重建、实例增删、材质表替换时让查询索引和数据版本同时生效；旧 TLAS、旧表和旧纹理引用都需等 GPU 完成后释放。否则视觉错误可能表现为偶发错误材质，严重时是越界读取。

### Alpha test 的完整确认流程

下列 `loadCandidateSurface` 与 `sampleAlpha` 是需要宿主数据支持的自定义函数：

```glsl
rayQueryEXT q;
// 强制所有三角形成为可供应用筛选的候选。
rayQueryInitializeEXT(q, luminrt_Scene, gl_RayFlagsNoOpaqueEXT, 255u,
    origin, tMin, direction, tMax);
while (rayQueryProceedEXT(q)) {
    if (rayQueryGetIntersectionTypeEXT(q, false)
            != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
    Surface s = loadCandidateSurface(
        rayQueryGetIntersectionInstanceCustomIndexEXT(q, false),
        rayQueryGetIntersectionGeometryIndexEXT(q, false),
        rayQueryGetIntersectionPrimitiveIndexEXT(q, false),
        rayQueryGetIntersectionBarycentricsEXT(q, false));
    // 候选分支可能不满足隐式导数条件，使用显式 LOD。
    float alpha = sampleAlpha(s, rayTextureLod);
    if (!s.alphaTest || alpha >= s.alphaCutoff) {
        rayQueryConfirmIntersectionEXT(q);
    }
}
```

不确认对应 `ignoreIntersectionEXT`；确认后 `rayQueryTerminateEXT` 对应接受交点并结束搜索。不要用 terminate 拒绝交点。使用 `TerminateOnFirstHit` 的 alpha-aware 阴影仍需上述确认循环，且不要加 `Opaque`，否则会绕过 alpha 判定。默认 None flags 下已有 opaque 的 solid 三角形会直接参与提交，不一定暴露为候选；cutout/translucent 才需要应用处理。

Alpha blending 与 alpha test 不同。BVH candidate 顺序并非从近到远，不应在候选回调中直接做有序透明合成或连续介质积分。玻璃/水应逐段查询最近表面，更新介质和方向后继续；直线透射可在每个最近交点处乘透射率并推进 origin。共享边界、多层薄片要限制最大层数并避免重复命中。

## 14. 模拟 SBT、ray type、payload 和递归

在普通 pipeline 中，hit group 通常按以下公式选中：

```text
record = instanceSbtOffset + geometryIndex * rayTypeStride + rayTypeOffset
```

ray query 不执行 SBT lookup。即使调用 `rayQueryGetIntersectionInstanceShaderBindingTableRecordOffsetEXT`，返回的也只是实例记录里的数值；它不会运行任何 shader。当前自动场景 offset 恒为 0，不含材质意义。

迁移时把记录保存为普通 SSBO 的 `HitRecord`，或者先查 Instance/Geometry/Material 表，再按 `material.kind` 调用 GLSL 函数。若需要保留原来的公式，应由宿主另行发布 `instanceSbtOffset` 和 record 表。ray type 是调用者的普通变量：

```glsl
const uint RAY_RADIANCE = 0u;
const uint RAY_SHADOW = 1u;
struct RadiancePayload {
    vec3 radiance;
    vec3 throughput;
    uint rngState;
    int mediumId;
};
// 下列函数由光影包实现；query 结果经普通函数参数传递。
void shadeHit(HitRecord record, Surface surface, inout RadiancePayload p) {
    if (record.kind == 0u) shadeDiffuse(surface, p);
    else if (record.kind == 1u) shadeMetal(surface, p);
    else shadeDielectric(surface, p);
}
```

同一命中函数可调用一个**独立的** shadow query；这相当于 closest-hit 内发射遮挡射线。`rayQueryEXT` 是 opaque 类型，不能放进 payload/SSBO、复制或在 invocation 之间传递。保存 origin/direction/throughput 等普通数据，再在当前 invocation 初始化 query。也不要把辐射查询和阴影查询共用一个仍在 traversal 的对象。

GLSL 不支持递归函数。单条随机路径用 bounce 循环即可；确定性反射加折射同时分叉，需要显式固定大小的任务栈：每项保存 ray、weight、depth、medium。pop 一个任务，追踪并累加；push 两个子任务，分别乘 Fresnel 权重和透射权重。栈溢出/深度截断必须有确定处理，不能默默覆盖待执行任务。分叉任务数可能指数增长，通常改用按 Fresnel 概率随机选择一个分支，并用概率归一化其权重。

wavefront 架构也可把 ray 状态写入 SSBO 队列，分多个 compute pass 完成 trace/shade/compact；队列计数、容量、dispatch 间同步和资源生命周期需要另行实现。当前 LuminRT 没有自动 ray 队列、跨 pass payload 或 ray tracing shader 调度器。

## 15. AABB intersection 的等价实现示例

当前 `luminrt_Scene` 没有 AABB。以下假定宿主创建 AABB BLAS，并通过自定义名称注册 TLAS；用于说明如何迁移 procedural intersection shader。`loadSphere` 需要查询键对应的球数据，不能从 AABB 的候选 getter 得到球体定义。

```glsl
if (rayQueryGetIntersectionTypeEXT(q, false)
        == gl_RayQueryCandidateIntersectionAABBEXT) {
    Sphere sphere = loadSphere(
        rayQueryGetIntersectionInstanceCustomIndexEXT(q, false),
        rayQueryGetIntersectionGeometryIndexEXT(q, false),
        rayQueryGetIntersectionPrimitiveIndexEXT(q, false));
    vec3 o = rayQueryGetIntersectionObjectRayOriginEXT(q, false) - sphere.center;
    vec3 d = rayQueryGetIntersectionObjectRayDirectionEXT(q, false);
    // 保留变换后方向长度，否则非均匀缩放会破坏世界射线参数 t。
    float a = dot(d, d);
    float b = dot(o, d);
    float c = dot(o, o) - sphere.radius * sphere.radius;
    float disc = b * b - a * c;
    if (a > 0.0 && disc >= 0.0) {
        float root = sqrt(disc);
        float t0 = (-b - root) / a;
        float t1 = (-b + root) / a;
        float lower = rayQueryGetRayTMinEXT(q);
        float upper = tMax;
        if (rayQueryGetIntersectionTypeEXT(q, true)
                != gl_RayQueryCommittedIntersectionNoneEXT) {
            upper = rayQueryGetIntersectionTEXT(q, true);
        }
        float t = t0 >= lower ? t0 : t1;
        if (t >= lower && t <= upper && acceptSphereMaterial(sphere, t)) {
            rayQueryGenerateIntersectionEXT(q, t);
        }
    }
}
```

示例在没有 committed intersection 时使用初始化时保存的 tMax，已有提交时才读取 committed T。`GenerateIntersection` 会执行生成/提交逻辑，不会再调用 any-hit shader，材质拒绝逻辑必须在生成前完成。最终类型是 Generated，应保存足够的 procedural ID，再在最终命中时重算局部位置和法线；它没有三角形重心坐标，也没有独立的 hit attribute/payload 传递通道。AABB 范围必须包围实际形状，否则硬件 traversal 根本不会给出候选。

## 16. 从几何查询到光照积分

### 最先可实现的混合路径

已有光影包可以保留光栅 G-buffer：从深度重建可见表面 P，从包自己的法线/材质 buffer 读取 N 和 BRDF，以 `P + offset` 发射太阳阴影、距离 AO 或反射射线。太阳阴影用单位光方向和有限远距离；点光源用 `normalize(lightPos-P)`，`tMax` 必须小于光源距离，并跳过距离太近的退化射线。

距离 AO 是有限半径内的可见性估计；如果用余弦半球采样，平均未遮挡比例即可形成该采样约定下的 AO。它不是完整间接光照。没有第二次命中的材质表时，反射可以正确得到可见性/命中距离，但不能可靠地给离屏物体着色。屏幕重投影命中点只在其确实对应当前可见表面时能读取 G-buffer，否则会读到遮挡物的材质。

### 完整路径追踪的状态更新

记 `beta` 为 throughput，`L` 为累计辐射，`wo=-ray.direction`。每次最近命中执行：

1. miss：加入 `beta * environment(direction)`，退出；若使用环境 NEE，应对非 delta 上一段应用 MIS 权重。
2. 加入表面发光 `beta * emission`；启用 NEE 时，对可被直接光采样命中的发光表面应用一致的 MIS 权重，避免重复计算。
3. 从一个灯光或环境采样方向 wi 和光照 Le，计算包含灯光选择概率的方向 PDF；发射独立 alpha-aware shadow query 判断可见性。
4. 累加 `beta * f(wo,wi) * abs(dot(N,wi)) * Le / lightPdf`，并使用对应 BSDF PDF 的 MIS 权重。
5. 采样 BSDF 得到下一段 wi，更新 `beta *= f * abs(dot(N,wi)) / bsdfPdf`。对镜面 delta 事件使用离散采样权重，不能强行套用普通立体角 PDF。
6. 更新 origin、单位 direction、介质、前一段 PDF、是否 delta 和 bounce；合理深度后做 Russian roulette。

例如 diffuse 的余弦半球采样中 `f=albedo/pi`、`pdf=cos(theta)/pi`，比值恰好化简为 albedo；这个化简不能推广到所有 BRDF。折射要保持统一的辐射/重要性传输约定及相应 eta 因子，介质吸收按段长度积分。

Russian roulette 可取 `p = clamp(max(beta.r,max(beta.g,beta.b)), 0.05, 0.95)`；随机数大于 p 则退出，存活时 `beta /= p`。最大深度、有限 tMax 和强行裁剪亮度仍可能产生偏差，应作为性能策略记录，而非称为无偏实现。

通用 origin 偏移按出射方向选择几何法线的一侧：

```glsl
vec3 offsetOrigin(vec3 p, vec3 ng, vec3 wi, float eps) {
    return p + (dot(ng, wi) >= 0.0 ? ng : -ng) * eps;
}
```

eps 应结合浮点误差、坐标尺度和几何厚度选择。法线贴图法线用于 BRDF，几何法线用于偏移；透明层不能统一沿同一方向偏移。随机状态按 pixel/frame/sample/bounce 初始化，traversal 顺序可能改变，不能把候选次数用于决定路径的随机种子。

## 17. Compute pass、帧阶段和输出资源

一个屏幕大小 compute pass 的 Iris dispatch 约定如下；此段只展示 dispatch，写图像前还需声明并配置正确的目标资源：

```glsl
#version 460 core
layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
const vec2 workGroupsRender = vec2(1.0);
uniform float viewWidth;
uniform float viewHeight;
void main() {
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = ivec2(viewWidth, viewHeight);
    if (any(greaterThanEqual(pixel, size))) return;
    vec2 uv = (vec2(pixel) + 0.5) / vec2(size);
    // 计算射线、执行 query，写入单独配置的 image 输出。
}
```

`workGroupsRender` 按屏幕尺寸与 local size 计算 dispatch；`const ivec3 workGroups` 指定固定 group 数量，二者选其一。命名如 `composite.csh`，它在对应 composite graphics pass 前运行。无需照搬 `gl_LaunchIDEXT` / `gl_LaunchSizeEXT`；compute 使用 GlobalInvocationID，fragment 使用屏幕坐标。

compute 写 `colorimgN` 时，image 格式必须与目标 colortex 格式一致，目标尺寸也可能不等于屏幕。Iris 的 compute image 和后续 graphics ping-pong 翻转具有各自选取当前视图的规则；不要假定 compute 输出必然是随后 fragment 读取的那个物理 image。先用一个专用目标验证写入、读取、翻转顺序，避免同一 dispatch 对同一像素的输入输出竞态。AS 构建可见性由宿主提交路径处理，shader 的 `memoryBarrier` 不能代替宿主 AS build-to-query 同步。

动态几何在世界绘制准备阶段采集；setup/begin 在它之前运行，不应要求这些阶段已有本帧完整实体几何。推荐在世界几何完成后的 composite pass 首先验证。shader 顶点动画/位移没有反馈到 AS，水面、植物摆动等仍会出现光栅与查询不一致，需要宿主同步位移或接受该限制。

性能上优先复用已有第一表面、只对阴影用首命中、控制反弹次数和查询距离。降低 ray 分辨率时采用深度/法线引导上采样。跨帧累积必须进行运动重投影、深度/法线拒绝，并在镜头切换、尺寸变化、维度切换和光影重载时重置；AS 的自动更新不等于提供了正确的运动向量。

## 18. 宿主绑定与适配验收

普通光影包只声明 `luminrt_Scene`。模组集成自定义 TLAS 时，在 `ProgramResources.finish()` 及 pipeline 创建前注册：

```java
AccelerationStructureBindings.of(resources)
    .setAccelerationStructure("customScene", () -> currentTlas);
// 数组数量须与 GLSL 声明完全相同，供应器必须返回有效的已构建 TLAS。
AccelerationStructureBindings.of(resources)
    .setAccelerationStructures("customScenes", () -> List.of(tlasA, tlasB));
```

调用者负责构建、更新和持有 TLAS/BLAS，使用同一个 RHI/device，并在 render pass 外录制 AS 操作。供应器每次资源快照解析时求值；替换时要同时替换配套数据表，并保持旧资源在 GPU 使用期间有效。具体构建参数、更新约束及资源管理见 [加速结构 RHI 文档](acceleration-structures.md)，不要把 Java handle 编码成 shader 普通整数来替代 AS descriptor。

建议按顺序验收：

| 阶段 | 通过条件 |
| --- | --- |
| 离线编译 | 拓展开/关分支均能生成合法 SPIR-V，vertex/fragment 接口一致 |
| Iris 加载 | 实际预处理、shader patch 和 descriptor 绑定成功 |
| 几何诊断 | 重心图与区段/动态物体一致，移动相机后命中位置稳定 |
| 可见性 | 硬阴影起点无明显 acne；alpha 仅在确有材质数据后验收 |
| 数据契约 | geometry/primitive 对应正确顶点，实例变化无旧索引误用 |
| 多次反弹 | 离屏材质、折射介质、发光/MIS、随机采样分别通过固定场景检查 |
| 生命周期 | 卸载、维度切换、重载、开关切换无错误绑定和资源泄漏 |

本仓库随附示例仅覆盖第一项离线 shader 检查和几何诊断的代码基础；不能据此声称已通过实际 GPU、Iris 转换或完整材质渲染验收。

规范参考：[GLSL_EXT_ray_query](https://github.com/KhronosGroup/GLSL/blob/master/extensions/ext/GLSL_EXT_ray_query.txt)、[Vulkan Ray Traversal](https://docs.vulkan.org/spec/latest/chapters/raytraversal.html)。项目版本的 Iris/Minecraft 参考用于核对具体集成行为，未来版本需要重新核实。
