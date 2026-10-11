#ifndef LUMINRT_SCENE_UV_GLSL
#define LUMINRT_SCENE_UV_GLSL

// 保留块名由 LuminRT 自动绑定，不需要 shaders.properties 的 bufferObject 配置。
layout(std430) readonly buffer luminrt_SceneUV {
    uvec4 luminrt_uvRecords[];
};

// candidate 与 committed 命中均可使用；三个索引必须来自同一次场景查询。
bool luminrtGetHitUV(int instanceIndex, int geometryIndex, int primitiveIndex,
                     vec2 barycentrics, out vec2 uv) {
    uv = vec2(0.0);
    uint length = uint(luminrt_uvRecords.length());
    if (length == 0u || instanceIndex < 0 || geometryIndex < 0 || primitiveIndex < 0) return false;
    uvec4 header = luminrt_uvRecords[0];
    if (header.x != 1u || uint(instanceIndex) >= header.y || uint(instanceIndex) >= length - 1u) return false;
    uvec4 instance = luminrt_uvRecords[1u + uint(instanceIndex)];
    if (uint(geometryIndex) >= instance.y || instance.x >= length
            || uint(geometryIndex) >= length - instance.x) return false;
    uvec4 geometry = luminrt_uvRecords[instance.x + uint(geometryIndex)];
    if (geometry.z == 0u || uint(primitiveIndex) >= geometry.y || geometry.x >= length
            || length - geometry.x < 2u || uint(primitiveIndex) > (length - geometry.x - 2u) / 2u) return false;
    uint triangle = geometry.x + 2u * uint(primitiveIndex);
    vec4 first = uintBitsToFloat(luminrt_uvRecords[triangle]);
    vec2 third = uintBitsToFloat(luminrt_uvRecords[triangle + 1u].xy);
    uv = first.xy * (1.0 - barycentrics.x - barycentrics.y)
        + first.zw * barycentrics.x + third * barycentrics.y;
    return true;
}

#endif
