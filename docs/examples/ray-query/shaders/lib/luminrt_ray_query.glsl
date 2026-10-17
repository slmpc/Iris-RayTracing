#ifndef LUMINRT_RAY_QUERY_GLSL
#define LUMINRT_RAY_QUERY_GLSL

uniform accelerationStructureEXT luminrt_Scene;

// 场景头由 LuminRT 自动绑定；anchor 是实例平移的参考原点。
layout(std430) readonly buffer luminrt_SceneHeader {
    uvec4 luminrt_sceneInfo;   // x=版本, y=anchor 代数, z=实例数, w=保留
    ivec4 luminrt_sceneAnchor; // xyz=anchor 世界坐标（整数）
};

// 相机相对射线起点：整数部分先相减，再叠加小数，远坐标下仍保持精度。
vec3 luminrtSceneOrigin(ivec3 cameraPositionInt, vec3 cameraPositionFract) {
    return vec3(cameraPositionInt - luminrt_sceneAnchor.xyz) + cameraPositionFract;
}

// 仅适用于相机坐标可精确表示为 float 的场景；远坐标请使用上面的重载。
vec3 luminrtSceneOrigin(vec3 cameraPosition) {
    return cameraPosition - vec3(luminrt_sceneAnchor.xyz);
}

// anchor 重定位时代数递增；依赖世界坐标的历史/缓存此时必须失效。
uint luminrtSceneAnchorGeneration() { return luminrt_sceneInfo.y; }

struct LuminRTHit {
    bool found;
    float t;
    vec3 position; // anchor 相对坐标；世界位置为 anchor + position
    int instanceIndex;
    int geometryIndex;
    int primitiveIndex;
    vec2 barycentrics;
    bool frontFace;
};

// 此几何诊断把所有三角形视为实体表面，不代表材质 alpha test。
LuminRTHit luminrtTraceClosest(vec3 origin, vec3 direction, float tMin, float tMax) {
    LuminRTHit hit;
    hit.found = false;
    hit.t = tMax;
    hit.position = origin;
    hit.instanceIndex = -1;
    hit.geometryIndex = -1;
    hit.primitiveIndex = -1;
    hit.barycentrics = vec2(0.0);
    hit.frontFace = false;

    rayQueryEXT query;
    rayQueryInitializeEXT(query, luminrt_Scene, gl_RayFlagsNoneEXT, 255u,
        origin, tMin, direction, tMax);
    while (rayQueryProceedEXT(query)) {
        if (rayQueryGetIntersectionTypeEXT(query, false)
                == gl_RayQueryCandidateIntersectionTriangleEXT) {
            rayQueryConfirmIntersectionEXT(query);
        }
    }

    if (rayQueryGetIntersectionTypeEXT(query, true)
            == gl_RayQueryCommittedIntersectionTriangleEXT) {
        hit.found = true;
        hit.t = rayQueryGetIntersectionTEXT(query, true);
        hit.position = origin + hit.t * direction;
        hit.instanceIndex = rayQueryGetIntersectionInstanceCustomIndexEXT(query, true);
        hit.geometryIndex = rayQueryGetIntersectionGeometryIndexEXT(query, true);
        hit.primitiveIndex = rayQueryGetIntersectionPrimitiveIndexEXT(query, true);
        hit.barycentrics = rayQueryGetIntersectionBarycentricsEXT(query, true);
        hit.frontFace = rayQueryGetIntersectionFrontFaceEXT(query, true);
    }
    return hit;
}

// 只判断是否存在遮挡，首个命中无需是最近交点。
bool luminrtOccluded(vec3 origin, vec3 direction, float tMin, float tMax) {
    rayQueryEXT query;
    rayQueryInitializeEXT(query, luminrt_Scene,
        gl_RayFlagsOpaqueEXT | gl_RayFlagsTerminateOnFirstHitEXT,
        255u, origin, tMin, direction, tMax);
    while (rayQueryProceedEXT(query)) {}
    return rayQueryGetIntersectionTypeEXT(query, true)
        != gl_RayQueryCommittedIntersectionNoneEXT;
}

#endif
