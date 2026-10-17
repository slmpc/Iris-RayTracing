#version 460 core

#ifdef ENABLED_LUMIN_RAY_TRACING_EXTENSION
#extension GL_EXT_ray_query : require
#include "/lib/luminrt_ray_query.glsl"
#include "/lib/luminrt_scene_geometry.glsl"
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;
uniform ivec3 cameraPositionInt;
uniform vec3 cameraPositionFract;
uniform float far;
#endif

uniform sampler2D colortex0;
in vec2 texcoord;
layout(location = 0) out vec4 outColor;
/* RENDERTARGETS: 0 */

void main() {
#ifdef ENABLED_LUMIN_RAY_TRACING_EXTENSION
    // 使用常规 Iris 投影反矩阵的近平面，避免无限远投影的 w 为零。
    vec4 viewPoint = gbufferProjectionInverse * vec4(texcoord * 2.0 - 1.0, -1.0, 1.0);
    vec3 viewDirection = normalize(viewPoint.xyz / viewPoint.w);
    vec3 worldDirection = normalize(mat3(gbufferModelViewInverse) * viewDirection);
    LuminRTHit hit = luminrtTraceClosest(luminrtSceneOrigin(cameraPositionInt, cameraPositionFract), worldDirection, 0.01, max(far, 1.0));
    if (hit.found) {
        vec3 bary = vec3(1.0 - hit.barycentrics.x - hit.barycentrics.y, hit.barycentrics);
        float distanceFade = 1.0 / (1.0 + 0.02 * hit.t);
        vec2 uv;
        bool hasUV = luminrtGetHitUV(hit.instanceIndex, hit.geometryIndex,
            hit.primitiveIndex, hit.barycentrics, uv);
        outColor = vec4((hasUV ? vec3(fract(uv), 0.25) : bary) * distanceFade, 1.0);
    } else {
        outColor = vec4(0.03, 0.06, 0.12, 1.0);
    }
#else
    outColor = texture(colortex0, texcoord);
#endif
}
