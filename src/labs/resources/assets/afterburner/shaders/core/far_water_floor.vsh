#version 330
#extension GL_ARB_separate_shader_objects : require

// Afterburner: the floor under the far terrain's water, for a shader pack (see LodRenderer#floorPipeline). The far
// terrain keeps no ground under its water, and packs see through water to what's under it (dhDepthTex1): this puts a
// floor's depth there, so the water looks as deep as the sea instead of fading out over nothing.

#include <minecraft:globals.glsl>
#include <minecraft:terrainglobals.glsl>

layout(std140) uniform LodNode {
    ivec4 NodeOrigin;
    ivec4 NodeFlags;
};

layout(std140) uniform LodView {
    ivec4 DrawnY;
    // The pack's projection for the far terrain (its dhProjection), with OpenGL's depth.
    mat4 DhProjection;
};

// As far_terrain.vsh's: x, z and the face in bits 0-16 as (z * 129 + x) * 6 + face, the material in 17-19, y in 20-31.
layout(location = 0) in uint Position;
layout(location = 1) in vec4 Color;

// From the camera, in blocks.
layout(location = 0) out vec3 floorPos;

void main() {
    uint xzFace = Position & 131071u;
    vec3 voxel = vec3(float(xzFace / 6u % 129u), float(Position >> 20), float(xzFace / 774u));
    floorPos = vec3(NodeOrigin.xyz - CameraBlockPos) + voxel * float(NodeOrigin.w) + CameraOffset;
    gl_Position = DhProjection * (ModelViewMat * vec4(floorPos, 1.0));
#ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
#endif
}
