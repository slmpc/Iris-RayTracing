#ifndef IRISRT_RAY_QUERY_GLSL
#define IRISRT_RAY_QUERY_GLSL

uniform accelerationStructureEXT irisrt_Scene;

struct IrisRTHit {
    bool found;
    float t;
    vec3 position;
    int instanceIndex;
    int geometryIndex;
    int primitiveIndex;
    vec2 barycentrics;
    bool frontFace;
};

// 此几何诊断把所有三角形视为实体表面，不代表材质 alpha test。
IrisRTHit irisrtTraceClosest(vec3 origin, vec3 direction, float tMin, float tMax) {
    IrisRTHit hit;
    hit.found = false;
    hit.t = tMax;
    hit.position = origin;
    hit.instanceIndex = -1;
    hit.geometryIndex = -1;
    hit.primitiveIndex = -1;
    hit.barycentrics = vec2(0.0);
    hit.frontFace = false;

    rayQueryEXT query;
    rayQueryInitializeEXT(query, irisrt_Scene, gl_RayFlagsNoneEXT, 255u,
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
bool irisrtOccluded(vec3 origin, vec3 direction, float tMin, float tMax) {
    rayQueryEXT query;
    rayQueryInitializeEXT(query, irisrt_Scene,
        gl_RayFlagsOpaqueEXT | gl_RayFlagsTerminateOnFirstHitEXT,
        255u, origin, tMin, direction, tMax);
    while (rayQueryProceedEXT(query)) {}
    return rayQueryGetIntersectionTypeEXT(query, true)
        != gl_RayQueryCommittedIntersectionNoneEXT;
}

#endif
