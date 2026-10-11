# 几何 ray query 诊断示例

把这里 `shaders/` 的内容合入现有光影包的 `shaders/`；选择一个未被使用的 composite pass，
或备份后替换现有 `composite.vsh` / `composite.fsh`。这不是包含所有 gbuffers、final 和材质的独立完整光影包。

启用 LuminRT 拓展并使用支持 ray query 的 Vulkan 设备。此 pass 显示最近三角形的重心坐标颜色，
随距离变暗，未命中为深蓝；关闭拓展时复制 `colortex0`。
AS 内没有 UV/材质数据，本示例确认所有三角形候选，叶片和透明物体也视为完整表面。

`luminrt_ray_query.glsl` 的 `luminrtTraceClosest` 与 `luminrtOccluded` 可作为几何查询起点。
调用者须提供非零单位方向、有限坐标和合法的 `0 <= tMin <= tMax`。
相机生成示例针对常规透视投影；正交、全景、VR 或自定义抖动投影需要修改生成逻辑。

完整说明见 [光影适配与 ray query 指南](../../shaderpack-ray-query-guide.md)。
本示例通过独立 glslang Vulkan 编译检查；尚未通过游戏内 Iris patch/渲染验收。
