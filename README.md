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
- 开发运行时自动下载 Sodium 0.9.2（Minecraft 26.3 / Fabric）。

Iris 以本地文件形式加入客户端编译和运行类路径，不打包进 IrisRT。
安装模组时，需要另外安装 Fabric API、上述 Iris 和兼容的 Sodium 0.9.x。
Minecraft 26.3 使用官方命名，无需 Yarn 映射。

## 加速结构 RHI

已添加 Vulkan BLAS/TLAS 创建、构建、更新、设备地址与延迟释放支持；OpenGL 返回空支持。
接口使用、实现分析和验证命令见 [docs/acceleration-structures.md](docs/acceleration-structures.md)。
Iris 与当前 Minecraft 26.3 的参考源码在被 Git 忽略的 `references/` 下。

## 许可证

本项目原创内容暂为 **All Rights Reserved（保留所有权利）**，详见 `LICENSE`。
第三方依赖（包括 Iris 的 LGPL-3.0-only）及 Gradle Wrapper 保留各自许可证。
