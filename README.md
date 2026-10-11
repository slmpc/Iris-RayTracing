# IrisRT

Minecraft **26.3** 的客户端 Fabric 项目，包名为 `com.github.slmpc.irisrt`。
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

Iris 以本地文件形式加入客户端编译和运行类路径，不打包进 IrisRT。
安装模组时，需要另外安装 Fabric API、上述 Iris 和兼容的 Sodium 0.9.x。
Minecraft 26.3 使用官方命名，无需 Yarn 映射。

## 视频设置

Sodium 视频设置中的 **Iris-Raytracing → 设置** 提供拓展开关，默认关闭，配置保存在 `config/irisrt.properties`。
点击“应用”会保存配置并重新加载光影包。启用时，光影包的 GLSL 和属性预处理环境中定义 `ENABLED_LUMIN_RAY_TRACING_EXTENSION`；关闭时不定义。
光影包可使用 `#ifdef ENABLED_LUMIN_RAY_TRACING_EXTENSION` 选择对应代码路径。

## 加速结构 RHI

已添加 Vulkan BLAS/TLAS 创建、构建、更新、设备地址与延迟释放支持，并可将 TLAS 标量或数组绑定到 Iris graphics/compute pipeline，使用 `GL_EXT_ray_query` 查询。
通过 `AccelerationStructureBindings.of(programResources)` 按 shader 资源名称注册 TLAS；OpenGL 返回空支持。
接口使用、实现分析和验证命令见 [docs/acceleration-structures.md](docs/acceleration-structures.md)。
Iris 与当前 Minecraft 26.3 的参考源码在被 Git 忽略的 `references/` 下。

开启拓展后自动收集地形及主世界 staged vertex 路径中的实体、方块实体、移动方块和粒子顶点，
维护区段 BLAS、动态 BLAS 与相机相对 TLAS。光影声明 `uniform accelerationStructureEXT irisrt_Scene;`
即可自动绑定场景；动态拓扑不变时 refit，区段和拓扑变化时重建，卸载与重载会释放旧资源。
当前仅提供几何，尚无材质/UV/alpha test 数据，也不包含 shader 顶点位移。

## 许可证

本项目原创内容暂为 **All Rights Reserved（保留所有权利）**，详见 `LICENSE`。
第三方依赖（包括 Iris 的 LGPL-3.0-only）及 Gradle Wrapper 保留各自许可证。
