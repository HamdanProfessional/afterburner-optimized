#version 330
#extension GL_ARB_separate_shader_objects : require

// One level of Afterburner's depth pyramid: each texel keeps the farthest depth of the 2 x 2 texels below it.
// Depth is reversed (1 is near), so that is the smallest value.

uniform sampler2D Source;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 last = textureSize(Source, 0) - 1;
    ivec2 s = ivec2(gl_FragCoord.xy) * 2;
    float d = min(min(texelFetch(Source, min(s, last), 0).r, texelFetch(Source, min(s + ivec2(1, 0), last), 0).r),
            min(texelFetch(Source, min(s + ivec2(0, 1), last), 0).r, texelFetch(Source, min(s + ivec2(1, 1), last), 0).r));
    fragColor = vec4(d, 0.0, 0.0, 1.0);
}
