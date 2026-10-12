#version 150

// Afterburner: the terrain vertex shader for 16-byte chunk vertices (see CompactVertices). Feeds vanilla's terrain
// fragment shaders the same values vanilla's terrain vertex shader does.

#moj_import <light.glsl>
#moj_import <fog.glsl>

// xyz: position in 1/2048 blocks from 8 blocks before the section's corner; w: the section's place in its region
// (low byte) and the block light (high byte).
in uvec4 Position;
// rgb: the color; a: the sky light.
in vec4 Color;
in vec2 UV0;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
// The region's corner, relative to the camera.
uniform vec3 ChunkOffset;
uniform int FogShape;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;

void main() {
    uint slot = Position.w & 255u;
    vec3 sectionCorner = vec3(float(slot & 7u), float((slot >> 3u) & 3u), float((slot >> 5u) & 7u)) * 16.0;
    vec3 pos = vec3(Position.xyz) * (1.0 / 2048.0) - 8.0 + sectionCorner + ChunkOffset;
    // Two matrix-vector products instead of a matrix-matrix one per vertex: the driver doesn't regroup it by itself.
    gl_Position = ProjMat * (ModelViewMat * vec4(pos, 1.0));

    vertexDistance = fog_distance(pos, FogShape);
    ivec2 light = ivec2(int(Position.w >> 8u), int(round(Color.a * 255.0)));
    vertexColor = vec4(Color.rgb, 1.0) * minecraft_sample_lightmap(Sampler2, light);
    texCoord0 = UV0;
}
