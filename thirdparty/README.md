# 本地 Iris Vulkan 依赖

本项目依赖尚未发布的 Iris Vulkan `1.11.6-snapshot+mc26.3-local`，仓库不包含该 JAR，也不会自动下载它。

构建或运行开发客户端前，请自行从该版本的提供方获取 JAR，或从对应源码构建，然后手动放置为：

```text
thirdparty/iris-vk-1.11.6-snapshot+mc26.3-local.jar
```

请确保它是面向 Minecraft **26.3**、使用官方命名的对应 Iris Vulkan 构建。普通 Iris 发布版或其他 Minecraft 版本的 JAR 不能直接替代；仅修改文件名也无法保证兼容性。

Gradle 从此本地路径读取依赖；版本号由 `gradle/libs.versions.toml` 中的 `iris` 条目指定。缺少该文件时无法正常编译或运行。放置文件后，在项目根目录执行：

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

需要 JDK 25。此依赖不打包进 LuminRT，实际安装模组时仍须另行安装兼容的 Iris、Fabric API 和 Sodium，详见根目录 [README](../README.md)。

`iris-vk-*.jar` 已被 Git 忽略，请勿将本地依赖 JAR 提交到仓库。
