package com.afterburner.labs.shaderpack;

/**
 * The GLSL of our FSR 1 upscaler (game.Upscaler): AMD FidelityFX Super Resolution 1, written after AMD's published algorithm
 * (MIT licensed). Kept here, free of game classes, so the offline checker compiles it too.
 */
public final class UpscaleShaders {
	/** The uniform block: the screen's width and height, and RCAS's sharpness as a factor. */
	public static final String BLOCK = "AbUpscale";
	/** The picture read. */
	public static final String SAMPLER = "InSampler";

	private UpscaleShaders() {
	}

	private static final String HEADER = """
		#version 330
		#extension GL_ARB_separate_shader_objects : require
		""";

	public static final String VERTEX = HEADER + """
		void main() {
			vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
			gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
		}
		""";

	/** What both passes share: the screen's size and the sharpness, and texel reads clamped to the picture. */
	private static final String COMMON = HEADER + """
		layout(std140) uniform AbUpscale {
			vec4 Upscale; // screen width, height; RCAS sharpness as a factor
		};
		uniform sampler2D InSampler;
		layout(location = 0) out vec4 ab_Color;

		vec3 fetch(ivec2 p) {
			return texelFetch(InSampler, clamp(p, ivec2(0), textureSize(InSampler, 0) - 1), 0).rgb;
		}

		// Twice a rough luma, as FSR uses it.
		float luma(vec3 c) {
			return c.b * 0.5 + (c.r * 0.5 + c.g);
		}
		""";

	/**
	 * EASU. Each screen pixel lands between four texels of the picture; around them, the 12 texels of a rounded 4x4 square are
	 * read. The four texels' neighbors give the direction of the edge there and how clear an edge it is; the 12 are then
	 * weighted by a Lanczos-like window, stretched along the edge and narrowed across it, so edges stay sharp. The result is
	 * kept between the four nearest texels, so there is no ringing.
	 */
	public static final String EASU = COMMON + """
		// One of the four texels: its share w of the edge's direction and clearness, from its neighbors above (a), left (b),
		// right (d) and below (e); c is the texel itself.
		void edge(inout vec2 dir, inout float len, float w, float a, float b, float c, float d, float e) {
			float dirX = d - b;
			float rangeX = max(abs(d - c), abs(c - b));
			float lenX = rangeX > 0.0 ? clamp(abs(dirX) / rangeX, 0.0, 1.0) : 0.0;
			dir.x += dirX * w;
			len += lenX * lenX * w;
			float dirY = e - a;
			float rangeY = max(abs(e - c), abs(c - a));
			float lenY = rangeY > 0.0 ? clamp(abs(dirY) / rangeY, 0.0, 1.0) : 0.0;
			dir.y += dirY * w;
			len += lenY * lenY * w;
		}

		// Adds a texel at offset off from the pixel. The offset is turned to the edge's direction and scaled (len2), and
		// weighted by (25/16 (2/5 x^2 - 1)^2 - 9/16) (lob x^2 - 1)^2, close to Lanczos 2 with a window of width 1/sqrt(lob).
		void tap(inout vec3 sum, inout float weight, vec2 off, vec2 dir, vec2 len2, float lob, float clp, vec3 c) {
			vec2 v = vec2(off.x * dir.x + off.y * dir.y, off.y * dir.x - off.x * dir.y) * len2;
			float d2 = min(dot(v, v), clp);
			float wB = 0.4 * d2 - 1.0;
			float wA = lob * d2 - 1.0;
			float w = (1.5625 * wB * wB - 0.5625) * (wA * wA);
			sum += c * w;
			weight += w;
		}

		void main() {
			// Where the pixel's middle is in the picture, in texels, and the texel f to its lower left.
			vec2 pp = (floor(gl_FragCoord.xy) + 0.5) * (vec2(textureSize(InSampler, 0)) / Upscale.xy) - 0.5;
			vec2 fp = floor(pp);
			pp -= fp;
			ivec2 at = ivec2(fp);
			//    b c
			//  e f g h
			//  i j k l
			//    n o
			vec3 b = fetch(at + ivec2(0, -1));
			vec3 c = fetch(at + ivec2(1, -1));
			vec3 e = fetch(at + ivec2(-1, 0));
			vec3 f = fetch(at);
			vec3 g = fetch(at + ivec2(1, 0));
			vec3 h = fetch(at + ivec2(2, 0));
			vec3 i = fetch(at + ivec2(-1, 1));
			vec3 j = fetch(at + ivec2(0, 1));
			vec3 k = fetch(at + ivec2(1, 1));
			vec3 l = fetch(at + ivec2(2, 1));
			vec3 n = fetch(at + ivec2(0, 2));
			vec3 o = fetch(at + ivec2(1, 2));
			float bL = luma(b), cL = luma(c), eL = luma(e), fL = luma(f), gL = luma(g), hL = luma(h);
			float iL = luma(i), jL = luma(j), kL = luma(k), lL = luma(l), nL = luma(n), oL = luma(o);

			vec2 dir = vec2(0.0);
			float len = 0.0;
			edge(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
			edge(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
			edge(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
			edge(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);

			float dirR = dot(dir, dir);
			dir = dirR < 1.0 / 32768.0 ? vec2(1.0, 0.0) : dir * inversesqrt(dirR);
			len *= 0.5;
			len *= len;
			// Stretched up to sqrt(2) along a diagonal edge, narrowed across a clear one.
			float stretch = dot(dir, dir) / max(abs(dir.x), abs(dir.y));
			vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
			float lob = 0.5 - 0.29 * len;
			float clp = 1.0 / lob;

			vec3 sum = vec3(0.0);
			float weight = 0.0;
			tap(sum, weight, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);
			tap(sum, weight, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);
			tap(sum, weight, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);
			tap(sum, weight, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);
			tap(sum, weight, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);
			tap(sum, weight, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);
			tap(sum, weight, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);
			tap(sum, weight, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);
			tap(sum, weight, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);
			tap(sum, weight, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);
			tap(sum, weight, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, o);
			tap(sum, weight, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);
			vec3 lo = min(min(f, g), min(j, k));
			vec3 hi = max(max(f, g), max(j, k));
			ab_Color = vec4(weight > 0.0 ? clamp(sum / weight, lo, hi) : f, 1.0);
		}
		""";

	/**
	 * RCAS. A pixel minus a share of its four neighbors (b above, d left, f right, h below) is sharper. The share is as much
	 * as keeps the result between 0 and 1 for every channel however the neighbors lie, at most 3/16, times the sharpness.
	 */
	public static final String RCAS = COMMON + """
		void main() {
			ivec2 p = ivec2(gl_FragCoord.xy);
			vec3 b = fetch(p + ivec2(0, -1));
			vec3 d = fetch(p + ivec2(-1, 0));
			vec3 e = fetch(p);
			vec3 f = fetch(p + ivec2(1, 0));
			vec3 h = fetch(p + ivec2(0, 1));
			vec3 lo = min(min(min(b, d), min(f, h)), e);
			vec3 hi = max(max(max(b, d), max(f, h)), e);
			// The most negative share that can't go below 0, and that can't go above 1.
			vec3 hitMin = lo / max(4.0 * hi, 1e-5);
			vec3 hitMax = (1.0 - hi) / min(4.0 * lo - 4.0, -1e-5);
			vec3 lobes = max(-hitMin, hitMax);
			float lobe = max(-0.1875, min(max(lobes.r, max(lobes.g, lobes.b)), 0.0)) * Upscale.z;
			ab_Color = vec4((lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0), 1.0);
		}
		""";
}
