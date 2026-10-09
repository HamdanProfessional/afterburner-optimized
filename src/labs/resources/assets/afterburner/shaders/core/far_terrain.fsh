#version 330
#extension GL_ARB_separate_shader_objects : require

// Afterburner: far terrain (see LodRenderer). Untextured: the color is the blocks' average.

#include <minecraft:fog.glsl>

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec3 lodClip;

// The same as far_terrain.vsh's.
layout(std140) uniform LodNode {
    ivec4 NodeOrigin;
    ivec4 NodeFlags;
};

layout(std140) uniform LodView {
    ivec4 DrawnY;
};

uniform usamplerBuffer LodMask;

layout(location = 0) out vec4 fragColor;

void main() {
    // A side next to the game's chunks (far_terrain.vsh): not where the game draws its own.
    if (lodClip.y > float(DrawnY.x) && lodClip.y < float(DrawnY.y)) {
        ivec2 chunk = (NodeOrigin.xz + ivec2(floor(lodClip.xz))) >> 4;
        if (texelFetch(LodMask, ((chunk.y & 255) << 8) | (chunk.x & 255)).r != 0u) discard;
    }
    fragColor = apply_fog(vertexColor, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
