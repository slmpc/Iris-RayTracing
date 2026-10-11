#version 460 core

in vec3 vaPosition;
in vec2 vaUV0;
out vec2 texcoord;

void main() {
    // Iris 的 composite 四边形位置范围为 0..1。
    gl_Position = vec4(vaPosition.xy * 2.0 - 1.0, 0.0, 1.0);
    texcoord = vaUV0;
}
