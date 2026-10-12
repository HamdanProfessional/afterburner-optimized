#version 150

// Afterburner: far terrain (see LodRenderer). Untextured: the color is the blocks' average.

uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
// The same as far_terrain.vsh's.
uniform ivec4 NodeOrigin;
uniform ivec4 DrawnY;
uniform usamplerBuffer LodMask;

in float vertexDistance;
in vec4 vertexColor;
in vec3 lodClip;

out vec4 fragColor;

// The same as the game's fog.glsl.
vec4 linear_fog(vec4 inColor, float vertexDistance, float fogStart, float fogEnd, vec4 fogColor) {
    if (vertexDistance <= fogStart) {
        return inColor;
    }
    float fogValue = vertexDistance < fogEnd ? smoothstep(fogStart, fogEnd, vertexDistance) : 1.0;
    return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
}

void main() {
    // A side next to the game's chunks (far_terrain.vsh): not where the game draws its own.
    if (lodClip.y > float(DrawnY.x) && lodClip.y < float(DrawnY.y)) {
        ivec2 chunk = (NodeOrigin.xz + ivec2(floor(lodClip.xz))) >> 4;
        if (texelFetch(LodMask, ((chunk.y & 255) << 8) | (chunk.x & 255)).r != 0u) discard;
    }
    fragColor = linear_fog(vertexColor, vertexDistance, FogStart, FogEnd, FogColor);
}
