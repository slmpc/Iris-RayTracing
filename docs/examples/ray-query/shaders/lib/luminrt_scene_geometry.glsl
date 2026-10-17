#ifndef LUMINRT_SCENE_GEOMETRY_GLSL
#define LUMINRT_SCENE_GEOMETRY_GLSL

// 场景目录由 LuminRT 自动绑定；记录缓冲按范围增量更新。
layout(std430) readonly buffer luminrt_SceneGeometry {
    uvec4 luminrt_geometry[];
};
layout(std430) readonly buffer luminrt_SceneRecords {
    uvec4 luminrt_records[];
};

// [0] = (版本, anchor 代数, 实例数, geometry 数)；[1] = (anchor.xyz, 0)。
const uint LUMINRT_GEOMETRY_VERSION = 1u;
const uint LUMINRT_FLAG_QUADS = 1u;
const uint LUMINRT_FLAG_OPAQUE = 2u;

uint luminrtSceneAnchorGeneration() {
    return luminrt_geometry[0].y;
}

ivec3 luminrtSceneAnchor() {
    return ivec4(luminrt_geometry[1]).xyz;
}

// 相机相对射线起点：整数部分先相减，再叠加小数，远坐标下仍保持精度。
vec3 luminrtSceneOrigin(ivec3 cameraPositionInt, vec3 cameraPositionFract) {
    return vec3(cameraPositionInt - luminrtSceneAnchor()) + cameraPositionFract;
}

// 仅适用于相机坐标可精确表示为 float 的场景；远坐标请使用上面的重载。
vec3 luminrtSceneOrigin(vec3 cameraPosition) {
    return cameraPosition - vec3(luminrtSceneAnchor());
}

// 每条记录 112 B = 7 个 uvec4：4 角位置、4 角 UV、tint 与材质。
struct LuminrtRecord {
    vec4 p0;
    vec4 p1;
    vec4 p2;
    vec4 p3;
    vec2 uv0;
    vec2 uv1;
    vec2 uv2;
    vec2 uv3;
    uint tint;
    uint material;
};

bool luminrtGetRecord(int instanceIndex, int geometryIndex, uint primitiveIndex,
                      out LuminrtRecord record, out uvec4 geometry) {
    record = LuminrtRecord(vec4(0), vec4(0), vec4(0), vec4(0), vec2(0), vec2(0), vec2(0), vec2(0), 0u, 0u);
    geometry = uvec4(0);
    uint length = uint(luminrt_geometry.length());
    if (length < 2u || instanceIndex < 0 || geometryIndex < 0) return false;
    uvec4 header = luminrt_geometry[0];
    if (header.x != LUMINRT_GEOMETRY_VERSION || uint(instanceIndex) >= header.z) return false;
    uint instanceRecord = 2u + uint(instanceIndex);
    if (instanceRecord >= length) return false;
    uvec4 instance = luminrt_geometry[instanceRecord];
    if (uint(geometryIndex) >= instance.y) return false;
    uint geometryRecord = 2u + header.z + instance.x + uint(geometryIndex);
    if (geometryRecord >= length) return false;
    geometry = luminrt_geometry[geometryRecord];
    uint quad = (geometry.z & LUMINRT_FLAG_QUADS) != 0u ? primitiveIndex >> 1 : primitiveIndex;
    if (quad >= geometry.y) return false;
    uint word = (geometry.x >> 4) + quad * 7u;
    if (word + 6u >= uint(luminrt_records.length())) return false;
    record.p0 = uintBitsToFloat(luminrt_records[word + 0u]);
    record.p1 = uintBitsToFloat(luminrt_records[word + 1u]);
    record.p2 = uintBitsToFloat(luminrt_records[word + 2u]);
    record.p3 = uintBitsToFloat(luminrt_records[word + 3u]);
    vec4 uv01 = uintBitsToFloat(luminrt_records[word + 4u]);
    vec4 uv23 = uintBitsToFloat(luminrt_records[word + 5u]);
    record.uv0 = uv01.xy;
    record.uv1 = uv01.zw;
    record.uv2 = uv23.xy;
    record.uv3 = uv23.zw;
    record.tint = luminrt_records[word + 6u].x;
    record.material = luminrt_records[word + 6u].y;
    return true;
}

// candidate 与 committed 命中均可使用；三个索引必须来自同一次场景查询。
bool luminrtGetHitUV(int instanceIndex, int geometryIndex, int primitiveIndex,
                     vec2 barycentrics, out vec2 uv) {
    uv = vec2(0.0);
    if (primitiveIndex < 0) return false;
    LuminrtRecord record;
    uvec4 geometry;
    if (!luminrtGetRecord(instanceIndex, geometryIndex, uint(primitiveIndex), record, geometry)) return false;
    float w0 = 1.0 - barycentrics.x - barycentrics.y;
    if ((geometry.z & LUMINRT_FLAG_QUADS) != 0u) {
        // 四边形固定 0-1-2 / 2-3-0；primitive 最低位选择半面。
        bool second = (uint(primitiveIndex) & 1u) != 0u;
        vec2 a = second ? record.uv2 : record.uv0;
        vec2 b = second ? record.uv3 : record.uv1;
        vec2 c = second ? record.uv0 : record.uv2;
        uv = a * w0 + b * barycentrics.x + c * barycentrics.y;
    } else {
        uv = record.uv0 * w0 + record.uv1 * barycentrics.x + record.uv2 * barycentrics.y;
    }
    return true;
}

// 材质身份低 16 位，bit16 为 opaque；其余位保留。
bool luminrtGetHitMaterial(int instanceIndex, int geometryIndex, int primitiveIndex,
                           out uint material, out uint tint) {
    material = 0u;
    tint = 0xffffffffu;
    if (primitiveIndex < 0) return false;
    LuminrtRecord record;
    uvec4 geometry;
    if (!luminrtGetRecord(instanceIndex, geometryIndex, uint(primitiveIndex), record, geometry)) return false;
    material = record.material;
    tint = record.tint;
    return true;
}

#endif
