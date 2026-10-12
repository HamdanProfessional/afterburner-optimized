#version 330
#extension GL_ARB_separate_shader_objects : require

// Afterburner: far terrain (see LodRenderer). A vertex is a corner in voxels from its node's corner and the face it
// belongs to, and a color with the light it gets.

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:terrainglobals.glsl>

layout(std140) uniform LodNode {
    // xyz: the node's corner in blocks (y: the world's bottom); w: blocks per voxel.
    ivec4 NodeOrigin;
    // x: 1 to hide it where the game draws its own chunks.
    ivec4 NodeFlags;
};

layout(std140) uniform LodView {
    // x to y: the heights (blocks) the game draws its chunks between; it draws only so many sections above and below
    // the camera's.
    ivec4 DrawnY;
};

// Per chunk (256 x 256, wrapping around): not 0 where the game draws it (2: not all of its land).
uniform usamplerBuffer LodMask;
uniform sampler2D Sampler2;

// x: bits 0-7, z: 8-15, y: 16-27, face: 28-30 (down, up, north, south, west, east).
layout(location = 0) in uint Position;
// rgb: the color; a: sky light (high 4 bits) and block light.
layout(location = 1) in vec4 Color;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
// A side of a node next to the game's chunks: where it is (x, z: blocks from the node's corner, a step into its own
// column; y: blocks), for far_terrain.fsh to hide what's between the heights the game draws. Else y is far below.
layout(location = 3) out vec3 lodClip;

const float SHADE[6] = float[](0.5, 1.0, 0.8, 0.8, 0.6, 0.6);
// From each corner a step into its quad, in x and z voxels, so the chunk under it is the quad's own.
const vec2 INWARD[24] = vec2[](
    vec2(0.5, -0.5), vec2(0.5, 0.5), vec2(-0.5, 0.5), vec2(-0.5, -0.5),
    vec2(0.5, 0.5), vec2(0.5, -0.5), vec2(-0.5, -0.5), vec2(-0.5, 0.5),
    vec2(-0.5, 0.5), vec2(-0.5, 0.5), vec2(0.5, 0.5), vec2(0.5, 0.5),
    vec2(0.5, -0.5), vec2(0.5, -0.5), vec2(-0.5, -0.5), vec2(-0.5, -0.5),
    vec2(0.5, 0.5), vec2(0.5, 0.5), vec2(0.5, -0.5), vec2(0.5, -0.5),
    vec2(-0.5, -0.5), vec2(-0.5, -0.5), vec2(-0.5, 0.5), vec2(-0.5, 0.5)
);

void main() {
    // See LodMesher: x, z and the face in bits 0-16 as (z * 129 + x) * 6 + face, the material in 17-19, y in 20-31.
    uint xzFace = Position & 131071u;
    int face = int(xzFace % 6u);
    vec3 voxel = vec3(float(xzFace / 6u % 129u), float(Position >> 20), float(xzFace / 774u));
    float scale = float(NodeOrigin.w);

    lodClip = vec3(0.0, -1.0e9, 0.0);
    if (NodeFlags.x != 0) {
        vec2 inside = (voxel.xz + INWARD[face * 4 + (gl_VertexIndex & 3)]) * scale;
        float y = float(NodeOrigin.y) + voxel.y * scale;
        if (face >= 2) {
            // A side can reach from below the heights the game draws to above them: it's hidden pixel by pixel. (Moving
            // its corners can't do that, and turns one all between them around, to be seen from behind.)
            lodClip = vec3(inside.x, y, inside.y);
        } else {
            ivec2 chunk = (NodeOrigin.xz + ivec2(floor(inside))) >> 4;
            // Hidden only between the heights the game draws: what's above and below shows.
            float lo = float(DrawnY.x), hi = float(DrawnY.y);
            bool hide = texelFetch(LodMask, ((chunk.y & 255) << 8) | (chunk.x & 255)).r != 0u
                    && (face == 0 ? y >= lo && y + scale <= hi : y - scale >= lo && y <= hi);
            if (hide) {
                // All four corners land here: the quad has no area and isn't drawn.
                gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                sphericalVertexDistance = 0.0;
                cylindricalVertexDistance = 0.0;
                vertexColor = vec4(0.0);
                return;
            }
        }
    }

    vec3 pos = vec3(NodeOrigin.xyz - CameraBlockPos) + voxel * scale + CameraOffset;
    gl_Position = ProjMat * (ModelViewMat * vec4(pos, 1.0));

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    int light = int(round(Color.a * 255.0));
    vertexColor = vec4(Color.rgb * SHADE[face], 1.0) * sample_lightmap(Sampler2, ivec2((light & 15) << 4, (light >> 4) << 4));
}
