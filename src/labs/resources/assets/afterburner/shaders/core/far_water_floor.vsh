#version 150

// Afterburner: the floor under the far terrain's water, for a shader pack (see LodRenderer#floorProgram). The far
// terrain keeps no ground under its water, and packs see through water to what's under it (dhDepthTex1): this puts a
// floor's depth there, so the water looks as deep as the sea instead of fading out over nothing.

uniform mat4 ModelViewMat;
// The pack's projection for the far terrain (its dhProjection), with OpenGL's depth.
uniform mat4 DhProjection;
// As far_terrain.vsh's.
uniform ivec4 NodeOrigin;
uniform ivec3 CameraBlockPos;
uniform vec3 CameraOffset;

// As far_terrain.vsh's: x, z and the face in bits 0-16 as (z * 129 + x) * 6 + face, the material in 17-19, y in 20-31.
in uint Position;
in vec4 Color;

// From the camera, in blocks.
out vec3 floorPos;

void main() {
    uint xzFace = Position & 131071u;
    vec3 voxel = vec3(float(xzFace / 6u % 129u), float(Position >> 20u), float(xzFace / 774u));
    floorPos = vec3(NodeOrigin.xyz - CameraBlockPos) + voxel * float(NodeOrigin.w) + CameraOffset;
    gl_Position = DhProjection * (ModelViewMat * vec4(floorPos, 1.0));
}
