# 第三方许可与来源声明

## IrisRT

Copyright (c) 2026 slmpc。

IrisRT 的原创代码采用 GNU General Public License 第 3 版，仅限该版本（SPDX：`GPL-3.0-only`）。完整许可证见根目录 `LICENSE`，构建产物中为 `LICENSE_irisrt`。

## prime / Prime PT

地形与动态顶点加速结构的实现参考了 prime / Prime PT 的静态 BLAS 保留、拓扑与姿态变化分离，以及按 GPU 完成状态回收资源的设计思路。IrisRT 使用面向 Iris Vulkan 和 Minecraft 26.3 的 Java 实现；本项目没有分发 prime 的完整源码、原生运行时、NVIDIA SDK 或素材。

- 上游项目：<https://github.com/bWFuanVzYWth/prime>
- 核实版本：`dev` 分支提交 `e1917423d0c35f57742a2ca08e0f3258d0ff23c0`。
- 上游版权声明：`Copyright (c) 2026 linlin`；其他贡献者及第三方材料的权利依上游声明保留。
- 原创代码许可：`GPL-3.0-only`。本项目的 `LICENSE` 提供同一份未经修改的 GNU GPL v3 正文。
- 上游另有 GPL 第 7 条下的 Minecraft / NVIDIA 指定组件链接例外，见上游 `LICENSE-EXCEPTIONS`；例外仅在其规定的代码和组件范围内有效，不自动扩展至 IrisRT 的原创代码。
- 上游第三方代码、库和素材各自保留许可证，见上游 `THIRD_PARTY_NOTICES.md` 和 `licenses/`；本声明不将未纳入 IrisRT 的材料列为本项目的分发内容。

上游许可原文：[LICENSE](https://github.com/bWFuanVzYWth/prime/blob/e1917423d0c35f57742a2ca08e0f3258d0ff23c0/LICENSE)、[LICENSE-EXCEPTIONS](https://github.com/bWFuanVzYWth/prime/blob/e1917423d0c35f57742a2ca08e0f3258d0ff23c0/LICENSE-EXCEPTIONS)、[THIRD_PARTY_NOTICES.md](https://github.com/bWFuanVzYWth/prime/blob/e1917423d0c35f57742a2ca08e0f3258d0ff23c0/THIRD_PARTY_NOTICES.md)。

具体参考范围见 [加速结构实现说明](docs/acceleration-structures.md)。修改或引入上游代码时须保留对应版权、许可及来源声明。

## 外部依赖和构建工具

Iris、Sodium、Fabric Loader、Fabric API、GLSL Transformer、Gradle Wrapper 等外部组件保留其各自的版权和许可证。Iris Vulkan 本地 JAR 未随仓库或 IrisRT 构建产物分发，须由用户自行提供，见 [thirdparty/README.md](thirdparty/README.md)。

本项目的 GPL 许可不授予 Minecraft 或其他第三方软件的额外权利。下载、运行和分发这些组件时，仍须遵守其各自条款。
