#version 330
#extension GL_ARB_separate_shader_objects : require

// Afterburner: the floor under the far terrain's water (see far_water_floor.vsh). Depth only.

#include <minecraft:terrainglobals.glsl>

layout(std140) uniform LodView {
    ivec4 DrawnY;
    mat4 DhProjection;
};

layout(location = 0) in vec3 floorPos;

// A flat floor this many blocks under the water's top; looking across the water, at most this far along the look.
const float FLOOR_DEPTH = 16.0, MAX_RUN = 128.0;

void main() {
    vec3 dir = normalize(floorPos);
    float run = min(FLOOR_DEPTH / max(-dir.y, 1.0e-3), MAX_RUN);
    vec4 clip = DhProjection * (ModelViewMat * vec4(floorPos + dir * run, 1.0));
    // OpenGL's depth, as the pack's far terrain has it (its depth range is 0 to 1 either way, see far_water_floor.vsh).
    gl_FragDepth = clamp(clip.z / clip.w * 0.5 + 0.5, 0.0, 1.0);
}
