#version 330
#extension GL_ARB_separate_shader_objects : require

// Afterburner's occlusion test, one pixel per chunk section or entity box (256 per row): white if it may be seen,
// black if everything already drawn where it would be is nearer than its nearest corner.

layout(std140) uniform OcclusionInfo {
    mat4 ViewProjection;
    // Width and height of the screen, pyramid levels, whether depth goes from 0 to 1.
    ivec4 Screen;
    // Sections, boxes, pixels to spare around each box, whether a box partly off the screen counts as seen.
    ivec4 Counts;
};

// Per section: its lowest corner relative to the camera, then an unused texel. Per box: lowest, then highest corner.
uniform samplerBuffer OcclusionBoxes;
uniform sampler2D Level0;
uniform sampler2D Level1;
uniform sampler2D Level2;
uniform sampler2D Level3;
uniform sampler2D Level4;
uniform sampler2D Level5;
uniform sampler2D Level6;
uniform sampler2D Level7;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// Block models may reach a block past their section, and the depth buffer rounds.
const float GROW = 1.25;

float fetch(int level, ivec2 p) {
    switch (level) {
        case 0: return texelFetch(Level0, p, 0).r;
        case 1: return texelFetch(Level1, p, 0).r;
        case 2: return texelFetch(Level2, p, 0).r;
        case 3: return texelFetch(Level3, p, 0).r;
        case 4: return texelFetch(Level4, p, 0).r;
        case 5: return texelFetch(Level5, p, 0).r;
        case 6: return texelFetch(Level6, p, 0).r;
        default: return texelFetch(Level7, p, 0).r;
    }
}

bool visible(vec3 lo, vec3 hi, bool whole) {
    vec2 low = vec2(1e30), high = vec2(-1e30);
    float nearest = -1e30;
    for (int i = 0; i < 8; i++) {
        vec4 clip = ViewProjection * vec4((i & 1) != 0 ? hi.x : lo.x, (i & 2) != 0 ? hi.y : lo.y, (i & 4) != 0 ? hi.z : lo.z, 1.0);
        // A corner (nearly) behind the camera: the box can't be put on the screen, so it counts as seen.
        if (clip.w < 1.0) return true;
        vec3 ndc = clip.xyz / clip.w;
        low = min(low, ndc.xy);
        high = max(high, ndc.xy);
        nearest = max(nearest, Screen.w != 0 ? ndc.z : ndc.z * 0.5 + 0.5);
    }
    ivec2 screen = Screen.xy;
    vec2 size = vec2(screen);
    ivec2 p0 = ivec2(floor((low * 0.5 + 0.5) * size)) - Counts.z;
    ivec2 p1 = ivec2(floor((high * 0.5 + 0.5) * size)) + Counts.z;
    if (whole && (any(lessThan(p0, ivec2(0))) || any(greaterThan(p1, screen - 1)))) return true;
    p0 = max(p0, ivec2(0));
    p1 = min(p1, screen - 1);
    if (any(greaterThan(p0, p1))) return true;
    // The finest pyramid level where the box covers at most 2 x 2 texels; level k texels cover 2^(k+1) pixels.
    int level = 0;
    while (level < Screen.z - 1 && any(greaterThan((p1 >> (level + 1)) - (p0 >> (level + 1)), ivec2(1)))) level++;
    ivec2 t0 = p0 >> (level + 1), t1 = p1 >> (level + 1);
    // Still bigger than that at the last level: too big to say.
    if (any(greaterThan(t1 - t0, ivec2(1)))) return true;
    float farthest = min(min(fetch(level, t0), fetch(level, ivec2(t1.x, t0.y))), min(fetch(level, ivec2(t0.x, t1.y)), fetch(level, t1)));
    // Hidden only if everything already drawn there is nearer than the nearest corner of the box.
    return farthest <= nearest;
}

void main() {
    int id = int(gl_FragCoord.y) * 256 + int(gl_FragCoord.x);
    bool seen = true;
    if (id < Counts.x) {
        vec3 corner = texelFetch(OcclusionBoxes, id * 2).xyz;
        seen = visible(corner - GROW, corner + (16.0 + GROW), Counts.w != 0);
    } else if (id < Counts.x + Counts.y) {
        int b = Counts.x * 2 + (id - Counts.x) * 2;
        seen = visible(texelFetch(OcclusionBoxes, b).xyz - 0.25, texelFetch(OcclusionBoxes, b + 1).xyz + 0.25, true);
    }
    fragColor = vec4(seen ? 1.0 : 0.0);
}
