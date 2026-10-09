#version 330
#extension GL_ARB_separate_shader_objects : require

// Afterburner: the terrain vertex shader for 16-byte chunk vertices (see CompactVertices). Feeds vanilla's terrain
// fragment shader the same values the vanilla vertex shader does.

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:terrainglobals.glsl>
#include <minecraft:chunksection.glsl>

// xyz: position in 1/2048 blocks from 8 blocks before the section's corner; w: the section's place in its region
// (low byte) and the block light (high byte).
layout(location = 0) in uvec4 Position;
// rgb: the color; a: the sky light.
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;

uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) out float chunkVisibility;

void main() {
    uint slot = Position.w & 255u;
    vec3 sectionCorner = vec3(float(slot & 7u), float((slot >> 3) & 3u), float((slot >> 5) & 7u)) * 16.0;
    vec3 inRegion = vec3(Position.xyz) * (1.0 / 2048.0) - 8.0 + sectionCorner;
    // ChunkPosition is the region's corner.
    vec3 pos = inRegion + vec3(ChunkPosition - CameraBlockPos) + CameraOffset;
    // Two matrix-vector products instead of a matrix-matrix one per vertex: the driver doesn't regroup it by itself.
    gl_Position = ProjMat * (ModelViewMat * vec4(pos, 1.0));

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    ivec2 light = ivec2(int(Position.w >> 8), int(round(Color.a * 255.0)));
    vertexColor = vec4(Color.rgb, 1.0) * sample_lightmap(Sampler2, light);
    texCoord0 = UV0;

    const float fullyVisibleRange = 16.0;
    chunkVisibility = mix(1.0, ChunkVisibility, clamp((sphericalVertexDistance - fullyVisibleRange) / fullyVisibleRange, 0.0, 1.0));
}
