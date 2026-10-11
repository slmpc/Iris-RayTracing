# LuminRT

LuminRT 是 **Iris Vulkan 的光线追踪拓展**，面向 Minecraft **26.3** 的 Fabric 客户端，包名为 `com.github.slmpc.luminrt`。
目前仅支持 **RayQuery（`GL_EXT_ray_query`）**，光影包通过 graphics/compute shader 查询场景加速结构；暂不支持独立的 Ray Tracing Pipeline（`VK_KHR_ray_tracing_pipeline` / `traceRayEXT`）。
Gradle 使用 Kotlin DSL（`.gradle.kts`），模组入口使用 Java 25。
依赖及 Loom 插件版本统一在 `gradle/libs.versions.toml` 中管理。

## 开发

安装 JDK 25，将 `JAVA_HOME` 指向该 JDK，然后运行：

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

Linux/macOS 使用 `./gradlew`。构建产物位于 `build/libs/`。

## 依赖

- Fabric Loader 0.19.5、Fabric API 0.162.0+26.3。
- 本地 Iris：`thirdparty/iris-vk-1.11.6-snapshot+mc26.3-local.jar`。
- 开发编译和运行时自动下载 Sodium 0.9.2（Minecraft 26.3 / Fabric）。

此 Iris Vulkan 版本尚未发布，仓库不提供其 JAR。构建前需自行获取对应版本，按 [thirdparty/README.md](thirdparty/README.md) 手动放入 `thirdparty/`。
Iris 以本地文件形式加入客户端编译和运行类路径，不打包进 LuminRT。
安装模组时，需要另外安装 Fabric API、上述 Iris 和兼容的 Sodium 0.9.x。
Minecraft 26.3 使用官方命名，无需 Yarn 映射。

## 视频设置

Sodium 视频设置中的 **LuminRT → 设置** 提供拓展开关，默认关闭，配置保存在 `config/luminrt.properties`。
点击“应用”会保存配置并重新加载光影包。启用时，光影包的 GLSL 和属性预处理环境中定义 `ENABLED_LUMIN_RAY_TRACING_EXTENSION`；关闭时不定义。
光影包可使用 `#ifdef ENABLED_LUMIN_RAY_TRACING_EXTENSION` 选择对应代码路径。

从旧版 IrisRT 升级时，请将 `config/irisrt.properties` 手动改名为 `config/luminrt.properties` 以保留设置；否则使用默认关闭状态。
光影包的场景 uniform 需从 `irisrt_Scene` 改为 `luminrt_Scene`，示例库文件及 helper 名称也已同步改名。
拓展开关宏 `ENABLED_LUMIN_RAY_TRACING_EXTENSION` 保持相同名称。

## 加速结构 RHI

已添加 Vulkan BLAS/TLAS 创建、构建、更新、设备地址与延迟释放支持，并可将 TLAS 标量或数组绑定到 Iris graphics/compute pipeline，使用 `GL_EXT_ray_query` 查询。
通过 `AccelerationStructureBindings.of(programResources)` 按 shader 资源名称注册 TLAS；OpenGL 返回空支持。
接口使用、实现分析和验证命令见 [docs/acceleration-structures.md](docs/acceleration-structures.md)。
Iris 与当前 Minecraft 26.3 的参考源码在被 Git 忽略的 `references/` 下。

开启拓展后自动收集地形及主世界 staged vertex 路径中的实体、方块实体、移动方块和粒子顶点，
地形接入 Sodium 0.9.2 的实际区段上传路径，兼容默认压缩格式及 Iris 扩展格式。
维护区段 BLAS、动态 BLAS 与相机相对 TLAS。光影声明 `uniform accelerationStructureEXT luminrt_Scene;`
即可自动绑定场景；动态拓扑不变时 refit，区段和拓扑变化时重建，卸载与重载会释放旧资源。
当前仅提供几何，尚无材质/UV/alpha test 数据，也不包含 shader 顶点位移。

## 光影包适配

完整的资源声明、坐标与几何约定，以及 ray tracing pipeline 到 ray query 的迁移方法，见 [光影包适配指南](docs/shaderpack-ray-query-guide.md)。
可合入现有光影包的 [几何诊断示例](docs/examples/ray-query/README.md) 提供最近命中和遮挡查询。

## 许可证

Copyright (c) 2026 slmpc。

本项目以 **GNU GPL 第 3 版（GPL-3.0-only）** 开源，完整条款见 [LICENSE](LICENSE)。你可以依据该许可证使用、修改和再分发本项目；本项目不提供任何担保。

地形与动态顶点加速结构的实现参考了 [prime / Prime PT](https://github.com/bWFuanVzYWth/prime) 的资源管理思路。prime 的版权归 linlin 及其贡献者所有，其原创代码采用 GPL-3.0-only，并附有单独的链接例外；详见 [第三方许可与来源声明](THIRD_PARTY_NOTICES.md)。

第三方依赖（包括 Iris 的 LGPL-3.0-only）及 Gradle Wrapper 保留各自许可证。本项目的许可证不替代第三方条款。

## AI / LLM 代码披露与合规声明

请注意，本代码库包含由大型语言模型（LLM）生成或辅助编写的软件组件。如果你的个人政策、监管要求或使用标准禁止部署或运行 AI 生成的软件，请立即停止使用 LuminRT，并从你的系统中移除所有 LuminRT 副本。

本声明用于披露 AI / LLM 的参与，不对 GPL-3.0-only 授予的权利附加限制。
