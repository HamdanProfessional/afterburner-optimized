#version 150

// Afterburner: far terrain (see LodRenderer). A vertex is a corner in voxels from its node's corner and the face it
// belongs to, and a color with the light it gets.

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
// xyz: the node's corner in blocks (y: the world's bottom); w: blocks per voxel.
uniform ivec4 NodeOrigin;
// x: 1 to hide it where the game draws its own chunks.
uniform ivec4 NodeFlags;
// The camera's block, and that block's corner minus where the camera is exactly.
uniform ivec3 CameraBlockPos;
uniform vec3 CameraOffset;
// x to y: the heights (blocks) the game draws its chunks between.
uniform ivec4 DrawnY;
// Per chunk (256 x 256, wrapping around): not 0 where the game draws it (2: not all of its land).
uniform usamplerBuffer LodMask;
uniform sampler2D Sampler2;

// See LodMesher: x, z and the face in bits 0-16 as (z * 129 + x) * 6 + face (down, up, north, south, west, east), the
// material in 17-19, y in 20-31.
in uint Position;
// rgb: the color; a: sky light (high 4 bits) and block light.
in vec4 Color;

out float vertexDistance;
out vec4 vertexColor;
// A side of a node next to the game's chunks: where it is (x, z: blocks from the node's corner, a step into its own
// column; y: blocks), for far_terrain.fsh to hide what's between the heights the game draws. Else y is far below.
out vec3 lodClip;

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

// The same as the game's fog.glsl.
float fog_distance(vec3 pos, int shape) {
    if (shape == 0) {
        return length(pos);
    }
    return max(length(pos.xz), abs(pos.y));
}

void main() {
    uint xzFace = Position & 131071u;
    int face = int(xzFace % 6u);
    vec3 voxel = vec3(float(xzFace / 6u % 129u), float(Position >> 20u), float(xzFace / 774u));
    float scale = float(NodeOrigin.w);

    lodClip = vec3(0.0, -1.0e9, 0.0);
    if (NodeFlags.x != 0) {
        // The quad index buffer counts its four corners from 0 in each quad.
        vec2 inside = (voxel.xz + INWARD[face * 4 + (gl_VertexID & 3)]) * scale;
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
                vertexDistance = 0.0;
                vertexColor = vec4(0.0);
                return;
            }
        }
    }

    vec3 pos = vec3(NodeOrigin.xyz - CameraBlockPos) + voxel * scale + CameraOffset;
    gl_Position = ProjMat * (ModelViewMat * vec4(pos, 1.0));

    vertexDistance = fog_distance(pos, FogShape);
    int light = int(round(Color.a * 255.0));
    // As the game's minecraft_sample_lightmap: block light across, sky light down.
    vec2 uv = vec2(float((light & 15) << 4), float((light >> 4) << 4));
    vertexColor = vec4(Color.rgb * SHADE[face], 1.0) * texture(Sampler2, clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
}
