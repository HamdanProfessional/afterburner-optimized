package com.afterburner.labs.shaderpack;

import com.afterburner.labs.shaderpack.GlslPreprocessor.Kind;
import com.afterburner.labs.shaderpack.GlslPreprocessor.Token;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Turns a pack program (preprocessed GLSL, any version from 120 up, compatibility built-ins and all) into the GLSL the game
 * compiles: {@code #version 330} (or the pack's, if newer) with separate shader objects, every in/out at an explicit location, plain uniforms in the
 * std140 blocks of a {@link UniformLayout}, and the old built-ins (gl_Vertex, gl_ModelViewMatrix, gl_FragData, texture2D, ...)
 * rebuilt from the game's own vertex inputs and uniform blocks.
 * <p>
 * Packs get normal OpenGL depth (near -1, far 1, depth cleared to 1, LESS) although the game uses reversed depth; see
 * {@link TranslateTarget}. Shadow samplers become plain samplers compared in the shader, as the game has no compare samplers.
 * <p>
 * Works on tokens, not a full parse: global declarations are found at the top level, everything else is renamed in place.
 */
public final class GlslTranslator {
	public enum Stage { VERTEX, FRAGMENT, COMPUTE }

	public static final class TranslateException extends Exception {
		public TranslateException(String message) {
			super(message);
		}
	}

	/**
	 * A global the pack declares with a storage qualifier. {@code qualifiers} are an image's format and memory qualifiers as
	 * written ("layout(r32ui) writeonly "), empty for anything else.
	 */
	record Decl(String storage, boolean flat, boolean noperspective, String type, String name, int arraySize, @Nullable String init,
			int location, String qualifiers) {}

	/** One stage, parsed and with the pack's interface declarations taken out. */
	public static final class Parsed {
		final Stage stage;
		final int version;
		final boolean gpuShader4;
		final List<Token> body;
		final List<Decl> decls;
		final Set<String> idents;
		final Set<String> functions;
		final List<String> origins;
		final List<GlslPreprocessor.Extension> extensions;
		final List<String> warnings = new ArrayList<>();
		/** Highest literal gl_FragData index, -1 if none. */
		int fragDataMax = -1;
		/** A compute stage's work group size as written ("layout(local_size_x = 8, ...) in;"), or null. */
		@Nullable String localSize;
		/** The storage buffer blocks the stage declares (left in its code): block name to its binding, -1 if it gives none. */
		final Map<String, Integer> buffers = new LinkedHashMap<>();
		/** The structs the stage defines: name to {the index of the definition's ';', how many varying locations one takes}. */
		final Map<String, int[]> structs = new HashMap<>();

		/** The names the stage's code (not its declarations) uses: uniforms it reads, images it writes, functions, ... */
		public Set<String> names() {
			return Collections.unmodifiableSet(this.idents);
		}

		/** How many outputs a fragment stage writes (gl_FragData indices or out variables); its default draw buffers are 0 to this - 1. */
		public int outputCount() {
			int count = this.fragDataMax + 1;
			int order = 0;
			for (Decl d : this.decls) {
				if (!d.storage().equals("out")) continue;
				count = Math.max(count, (d.location() >= 0 ? d.location() : order) + 1);
				order++;
			}
			return count;
		}
		/** Highest literal gl_TexCoord index, -1 if none, 7 if indexed by a variable. */
		int texCoordMax = -1;

		Parsed(Stage stage, int version, boolean gpuShader4, List<Token> body, List<Decl> decls, Set<String> idents, Set<String> functions,
				List<String> origins, List<GlslPreprocessor.Extension> extensions) {
			this.stage = stage;
			this.version = version;
			this.gpuShader4 = gpuShader4;
			this.body = body;
			this.decls = decls;
			this.idents = idents;
			this.functions = functions;
			this.origins = origins;
			this.extensions = extensions;
		}
	}

	public record Varying(String name, String type, int arraySize, boolean flat, boolean noperspective, int location) {}

	/** A translated program. */
	public static final class Program {
		public final String vertex;
		public final String fragment;
		/** Lines our code puts before the pack's in each stage. */
		final int vertexHeader;
		final int fragmentHeader;
		final List<String> vertexOrigins;
		final List<String> fragmentOrigins;
		/** GLSL names of the samplers, both stages. */
		public final Set<String> samplers;
		/** GLSL names of the images (custom images), both stages; bound by name, not by the pipeline (see ExternalBindings). */
		public final Set<String> images;
		/** The storage buffer blocks, both stages: block name to binding. */
		public final Map<String, Integer> buffers;
		/** Uniform blocks used, both stages: the game's (DynamicTransforms, Projection, ...) and {@link UniformLayout}'s. */
		public final Set<String> blocks;
		/** The buffer each fragment output writes, in gl_FragData order (-1: written nowhere). */
		public final int[] drawBuffers;
		public final List<Varying> varyings;
		public final List<String> warnings;

		Program(String vertex, String fragment, int vertexHeader, int fragmentHeader, List<String> vertexOrigins, List<String> fragmentOrigins,
				Set<String> samplers, Set<String> images, Map<String, Integer> buffers, Set<String> blocks, int[] drawBuffers, List<Varying> varyings,
				List<String> warnings) {
			this.vertex = vertex;
			this.fragment = fragment;
			this.vertexHeader = vertexHeader;
			this.fragmentHeader = fragmentHeader;
			this.vertexOrigins = vertexOrigins;
			this.fragmentOrigins = fragmentOrigins;
			this.samplers = samplers;
			this.images = images;
			this.buffers = buffers;
			this.blocks = blocks;
			this.drawBuffers = drawBuffers;
			this.varyings = varyings;
			this.warnings = warnings;
		}

		/** Where a line (1-based) of a translated stage came from: "file:line" in the pack, or "generated:N". */
		public String origin(Stage stage, int line) {
			int header = stage == Stage.VERTEX ? this.vertexHeader : this.fragmentHeader;
			List<String> origins = stage == Stage.VERTEX ? this.vertexOrigins : this.fragmentOrigins;
			int index = line - header - 1;
			return index >= 0 && index < origins.size() ? origins.get(index) : "generated:" + line;
		}
	}

	/**
	 * A translated compute program: GLSL 430, compiled by the driver as it is (the game's shader compiler takes vertex and
	 * fragment shaders only), with the same uniform blocks as the pack's other programs.
	 */
	public static final class Compute {
		public final String source;
		final int header;
		final List<String> origins;
		public final Set<String> samplers;
		public final Set<String> images;
		public final Map<String, Integer> buffers;
		public final List<String> warnings;

		Compute(String source, int header, List<String> origins, Set<String> samplers, Set<String> images, Map<String, Integer> buffers,
				List<String> warnings) {
			this.source = source;
			this.header = header;
			this.origins = origins;
			this.samplers = samplers;
			this.images = images;
			this.buffers = buffers;
			this.warnings = warnings;
		}

		/** Where a line (1-based) came from: "file:line" in the pack, or "generated:N". */
		public String origin(int line) {
			int index = line - this.header - 1;
			return index >= 0 && index < this.origins.size() ? this.origins.get(index) : "generated:" + line;
		}
	}

	private GlslTranslator() {
	}

	// ---- Tables ----

	private static final Set<String> QUALIFIERS = Set.of(
		"uniform", "attribute", "varying", "in", "out", "const", "flat", "smooth", "noperspective", "centroid", "invariant", "highp",
		"mediump", "lowp", "patch", "sample", "precise", "writeonly", "readonly", "coherent", "volatile", "restrict");
	private static final Set<String> STORAGE = Set.of("uniform", "attribute", "varying", "in", "out");
	/** Memory qualifiers of images. */
	private static final Set<String> MEMORY = Set.of("writeonly", "readonly", "coherent", "volatile", "restrict");
	/** Layout qualifiers that aren't an image's format. */
	private static final Set<String> BLOCK_LAYOUTS = Set.of("std140", "std430", "shared", "packed", "row_major", "column_major");

	/** Old texture functions and their GLSL 330 names. */
	private static final Map<String, String> FUNCTIONS = Map.ofEntries(
		Map.entry("texture1D", "texture"), Map.entry("texture1DLod", "textureLod"), Map.entry("texture1DProj", "textureProj"),
		Map.entry("texture2D", "texture"), Map.entry("texture2DLod", "textureLod"), Map.entry("texture2DProj", "textureProj"),
		Map.entry("texture2DProjLod", "textureProjLod"), Map.entry("texture2DLodEXT", "textureLod"), Map.entry("texture2DLodARB", "textureLod"),
		Map.entry("texture2DGrad", "textureGrad"), Map.entry("texture2DGradARB", "textureGrad"), Map.entry("texture2DGradEXT", "textureGrad"),
		Map.entry("texture2DProjGradARB", "textureProjGrad"), Map.entry("texture2DRect", "texture"), Map.entry("texture3D", "texture"),
		Map.entry("texture3DLod", "textureLod"), Map.entry("texture3DProj", "textureProj"), Map.entry("textureCube", "texture"),
		Map.entry("textureCubeLod", "textureLod"), Map.entry("textureCubeLodEXT", "textureLod"), Map.entry("texelFetch1D", "texelFetch"),
		Map.entry("texelFetch2D", "texelFetch"), Map.entry("texelFetch3D", "texelFetch"), Map.entry("texelFetch2DOffset", "texelFetchOffset"),
		Map.entry("textureSize2D", "textureSize"), Map.entry("textureSize1D", "textureSize"), Map.entry("texture2DOffset", "textureOffset"),
		Map.entry("texture2DLodOffset", "textureLodOffset"), Map.entry("shadow2D", "ab_shadow2D"), Map.entry("shadow2DLod", "ab_shadow2DLod"),
		Map.entry("shadow2DProj", "ab_shadow2DProj"), Map.entry("shadow2DProjLod", "ab_shadow2DProjLod"),
		Map.entry("shadow2DGradARB", "ab_shadow2DLod0"), Map.entry("shadow2DLodEXT", "ab_shadow2DLod"));

	/** Built-in functions of GLSL 330 that 120 lacked, which a 120 pack may have defined itself. */
	private static final Set<String> NEW_FUNCTIONS = Set.of(
		"round", "roundEven", "trunc", "sinh", "cosh", "tanh", "asinh", "acosh", "atanh", "isnan", "isinf", "modf", "floatBitsToInt",
		"floatBitsToUint", "intBitsToFloat", "uintBitsToFloat", "inverse", "determinant", "texture", "textureProj", "textureLod",
		"textureOffset", "texelFetch", "texelFetchOffset", "textureProjOffset", "textureLodOffset", "textureProjLod", "textureProjLodOffset",
		"textureGrad", "textureGradOffset", "textureProjGrad", "textureProjGradOffset", "textureSize");

	/**
	 * Built-in functions of GLSL 400 and 420 that the game's compiler has in version 330 too, which an older pack may have
	 * defined itself (Bliss's fma).
	 */
	private static final Set<String> FUNCTIONS_400 = Set.of(
		"fma", "bitCount", "findLSB", "findMSB", "bitfieldExtract", "bitfieldInsert", "bitfieldReverse", "frexp", "ldexp", "uaddCarry", "usubBorrow",
		"umulExtended", "imulExtended", "packUnorm4x8", "packSnorm4x8", "unpackUnorm4x8", "unpackSnorm4x8", "packUnorm2x16", "packSnorm2x16",
		"unpackUnorm2x16", "unpackSnorm2x16", "packDouble2x32", "unpackDouble2x32", "textureGather", "textureGatherOffset", "textureQueryLod",
		"interpolateAtCentroid", "interpolateAtSample", "interpolateAtOffset");
	private static final Set<String> FUNCTIONS_420 = Set.of("packHalf2x16", "unpackHalf2x16");

	/** Words GLSL 330 reserves that 120 code may use as names. */
	private static final Set<String> NEW_KEYWORDS = Set.of(
		"layout", "smooth", "noperspective", "flat", "uint", "uvec2", "uvec3", "uvec4", "common", "partition", "active", "superp", "filter",
		"row_major", "switch", "case", "default", "sampler2DArray", "isampler2D", "usampler2D", "samplerBuffer");
	/** ... of which these are types: a 120 pack that uses them as types (as drivers let it) keeps them. */
	private static final Set<String> NEW_TYPES = Set.of("uint", "uvec2", "uvec3", "uvec4", "isampler2D", "usampler2D", "sampler2DArray", "samplerBuffer");
	/** ... of which EXT_gpu_shader4 already had these. */
	private static final Set<String> GPU_SHADER4_KEYWORDS = Set.of("flat", "noperspective", "uint", "uvec2", "uvec3", "uvec4");
	/**
	 * Words the game's compiler reserves (it compiles for Vulkan) that OpenGL's GLSL doesn't, which packs use as names
	 * ("vec4 bicubic(sampler2D sampler, vec2 uv)").
	 */
	private static final Set<String> VULKAN_KEYWORDS = Set.of(
		"sampler", "samplerShadow", "textureBuffer", "itextureBuffer", "utextureBuffer", "texture1DArray", "texture2DArray", "textureCubeArray",
		"texture2DMS", "texture2DMSArray", "subpassInput", "isubpassInput", "usubpassInput", "subpassInputMS", "isubpassInputMS", "usubpassInputMS");
	/** Shadow sampler types and the plain ones they become (the game has no compare samplers). */
	private static final Map<String, String> SHADOW_TYPES = Map.of(
		"sampler1DShadow", "sampler1D", "sampler2DShadow", "sampler2D", "sampler2DArrayShadow", "sampler2DArray", "samplerCubeShadow", "samplerCube",
		"sampler2DRectShadow", "sampler2DRect");

	/** Names our generated code declares (vertex elements, the game's blocks and their members); a pack's own that clash get "_ab". */
	private static final Set<String> RESERVED = new HashSet<>(Set.of(
		"Position", "Color", "UV0", "UV1", "UV2", "UV3", "Normal", "LineWidth", "Sampler0", "Sampler1", "Sampler2", "Lighting",
		"AbMidTex", "AbNormal", "AbBlock", "ab_unoct",
		UniformLayout.FRAME_BLOCK, UniformLayout.DRAW_BLOCK));

	static {
		for (Map.Entry<String, List<String>> block : TranslateTarget.GAME_BLOCKS.entrySet()) {
			RESERVED.add(block.getKey());
			for (String member : block.getValue()) RESERVED.add(member.substring(member.indexOf(' ') + 1));
		}
	}

	/**
	 * Uniforms from OptiFine's 1.17+ list that are the game's own values, and alphaTestRef (the program's alpha test reference, see
	 * {@link TranslateTarget#withAlphaTest}): they become globals set from those.
	 */
	private static final Map<String, String> UNIFORM_ALIASES = Map.of(
		"modelViewMatrix", "ab_ModelViewMatrix",
		"modelViewMatrixInverse", "inverse(ab_ModelViewMatrix)",
		"projectionMatrix", "ab_ProjectionMatrix",
		"projectionMatrixInverse", "inverse(ab_ProjectionMatrix)",
		"textureMatrix", "ab_TextureMatrix[0]",
		"normalMatrix", "ab_NormalMatrix",
		"colorModulator", "vec4(1.0)",
		"modelOffset", "vec3(0.0)",
		"chunkOffset", "vec3(0.0)",
		"alphaTestRef", "0.1");

	/**
	 * What Iris declares by itself when a pack uses it without declaring it: the 1.17+ attributes and matrices (Complementary's
	 * gbuffers_line relies on this). Name to type.
	 */
	private static final Map<String, String> IMPLICIT_UNIFORMS = Map.of(
		"modelViewMatrix", "mat4", "modelViewMatrixInverse", "mat4", "projectionMatrix", "mat4", "projectionMatrixInverse", "mat4",
		"textureMatrix", "mat4", "normalMatrix", "mat3", "chunkOffset", "vec3");
	private static final Map<String, String> IMPLICIT_ATTRIBUTES = Map.of(
		"vaPosition", "vec3", "vaColor", "vec4", "vaUV0", "vec2", "vaUV1", "ivec2", "vaUV2", "ivec2", "vaNormal", "vec3",
		// Distant Horizons' block material, in its dh_ programs.
		"dhMaterialId", "int");

	/** Defaults for attributes the target doesn't give, as GL gives for an attribute with no data. */
	private static final Map<String, String> ATTRIBUTE_DEFAULTS = Map.of(
		"mc_Entity", "vec4(-1.0, -1.0, 0.0, 1.0)",
		"mc_midTexCoord", "vec4(0.0, 0.0, 0.0, 1.0)",
		"at_tangent", "vec4(1.0, 0.0, 0.0, 1.0)",
		"at_midBlock", "vec4(0.0)",
		"at_velocity", "vec4(0.0)",
		"blockEntityId", "vec4(0.0)",
		"gl_SecondaryColor", "vec4(0.0, 0.0, 0.0, 1.0)",
		"gl_FogCoord", "vec4(0.0)",
		"dhMaterialId", "0");

	/** Unfolds a unit vector folded onto an octahedron (as CompactVertices writes normals and tangents). */
	private static final String UNOCT = """
		vec3 ab_unoct(vec2 e) {
			vec3 n = vec3(e, 1.0 - abs(e.x) - abs(e.y));
			float t = max(-n.z, 0.0);
			n.x += n.x >= 0.0 ? -t : t;
			n.y += n.y >= 0.0 ? -t : t;
			return normalize(n);
		}
		""";

	private static final String TO_GL = """
		#ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
		const mat4 ab_ToGl = mat4(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, -2.0, 0.0, 0.0, 0.0, 1.0, 1.0);
		#else
		const mat4 ab_ToGl = mat4(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, 1.0);
		#endif
		""";

	/** OptiFine's lightmap texture matrix (TEXTURE_MATRIX_2). */
	private static final String LIGHTMAP_MATRIX = "mat4(0.00390625, 0.0, 0.0, 0.0, 0.0, 0.00390625, 0.0, 0.0, 0.0, 0.0, 0.00390625, 0.0, 0.03125, 0.03125, 0.03125, 1.0)";

	/** Depth compare done in the shader, with the 2x2 filtering hardware compare samplers give. */
	private static final String SHADOW_HELPERS = """
		float ab_shadowCompare(sampler2D s, vec3 c) {
			ivec2 size = textureSize(s, 0);
			vec2 p = c.xy * vec2(size) - 0.5;
			vec2 f = fract(p);
			ivec2 i = ivec2(floor(p));
			ivec2 m = size - 1;
			float a = step(c.z, texelFetch(s, clamp(i, ivec2(0), m), 0).r);
			float b = step(c.z, texelFetch(s, clamp(i + ivec2(1, 0), ivec2(0), m), 0).r);
			float d = step(c.z, texelFetch(s, clamp(i + ivec2(0, 1), ivec2(0), m), 0).r);
			float e = step(c.z, texelFetch(s, clamp(i + ivec2(1, 1), ivec2(0), m), 0).r);
			return mix(mix(a, b, f.x), mix(d, e, f.x), f.y);
		}
		vec4 ab_shadow2D(sampler2D s, vec3 c) { return vec4(ab_shadowCompare(s, c)); }
		vec4 ab_shadow2D(sampler2D s, vec3 c, float bias) { return vec4(ab_shadowCompare(s, c)); }
		vec4 ab_shadow2DLod(sampler2D s, vec3 c, float lod) { return vec4(ab_shadowCompare(s, c)); }
		vec4 ab_shadow2DLod0(sampler2D s, vec3 c, vec2 dx, vec2 dy) { return vec4(ab_shadowCompare(s, c)); }
		vec4 ab_shadow2DProj(sampler2D s, vec4 c) { return vec4(ab_shadowCompare(s, c.xyz / c.w)); }
		vec4 ab_shadow2DProj(sampler2D s, vec4 c, float bias) { return vec4(ab_shadowCompare(s, c.xyz / c.w)); }
		vec4 ab_shadow2DProjLod(sampler2D s, vec4 c, float lod) { return vec4(ab_shadowCompare(s, c.xyz / c.w)); }
		float ab_shadowTexture(sampler2D s, vec3 c) { return ab_shadowCompare(s, c); }
		float ab_shadowTexture(sampler2D s, vec3 c, float bias) { return ab_shadowCompare(s, c); }
		float ab_shadowTextureLod(sampler2D s, vec3 c, float lod) { return ab_shadowCompare(s, c); }
		float ab_shadowTextureProj(sampler2D s, vec4 c) { return ab_shadowCompare(s, c.xyz / c.w); }
		""";

	/** textureGather of a shadow sampler (version 400 up): the four texels compared. */
	private static final String SHADOW_GATHER = """
		vec4 ab_shadowTextureGather(sampler2D s, vec2 p, float z) { return step(vec4(z), textureGather(s, p, 0)); }
		vec4 ab_shadowTextureGatherOffset(sampler2D s, vec2 p, float z, ivec2 o) { return step(vec4(z), textureGatherOffset(s, p, o, 0)); }
		""";

	/**
	 * textureGather of another component than red: GLSL 400's, which the game's GLSL 330 can't give, so the four texels (in
	 * textureGather's order) are fetched.
	 */
	private static final String GATHER = """
		vec4 ab_gather(vec4 a, vec4 b, vec4 d, vec4 e, int c) { return vec4(a[c], b[c], d[c], e[c]); }
		vec4 ab_textureGatherOffset(sampler2D s, vec2 p, ivec2 o, int c) {
			ivec2 m = textureSize(s, 0) - 1;
			ivec2 i = ivec2(floor(p * vec2(m + 1) - 0.5)) + o;
			return ab_gather(texelFetch(s, clamp(i + ivec2(0, 1), ivec2(0), m), 0), texelFetch(s, clamp(i + ivec2(1, 1), ivec2(0), m), 0),
				texelFetch(s, clamp(i + ivec2(1, 0), ivec2(0), m), 0), texelFetch(s, clamp(i, ivec2(0), m), 0), c);
		}
		vec4 ab_textureGather(sampler2D s, vec2 p, int c) { return ab_textureGatherOffset(s, p, ivec2(0), c); }
		vec4 ab_textureGatherOffset(sampler2DArray s, vec3 p, ivec2 o, int c) {
			ivec2 m = textureSize(s, 0).xy - 1;
			ivec2 i = ivec2(floor(p.xy * vec2(m + 1) - 0.5)) + o;
			int l = int(p.z + 0.5);
			return ab_gather(texelFetch(s, ivec3(clamp(i + ivec2(0, 1), ivec2(0), m), l), 0), texelFetch(s, ivec3(clamp(i + ivec2(1, 1), ivec2(0), m), l), 0),
				texelFetch(s, ivec3(clamp(i + ivec2(1, 0), ivec2(0), m), l), 0), texelFetch(s, ivec3(clamp(i, ivec2(0), m), l), 0), c);
		}
		vec4 ab_textureGather(sampler2DArray s, vec3 p, int c) { return ab_textureGatherOffset(s, p, ivec2(0), c); }
		""";

	/** Iris's functions for Distant Horizons' textured LODs, and ours: our far terrain has no textures (its vertex color is all). */
	private static final Map<String, String> DH_FUNCTIONS = Map.of("dh_hasTexture", "ab_dhHasTexture", "dh_sampleTexture", "ab_dhSampleTexture");
	private static final String DH_TEXTURE = """
		bool ab_dhHasTexture() { return false; }
		vec4 ab_dhSampleTexture() { return vec4(1.0); }
		""";

	// ---- Parsing ----

	/** Parses a preprocessed stage. */
	public static Parsed parse(GlslPreprocessor.Result source, Stage stage) throws TranslateException {
		int version = 110;
		if (source.version != null) {
			String digits = source.version.replaceAll("[^0-9].*$", "");
			if (!digits.isEmpty()) version = Integer.parseInt(digits);
		}
		boolean gpuShader4 = source.extensions.stream().anyMatch(e -> e.name().equals("GL_EXT_gpu_shader4"));
		List<Token> tokens = flattenArrays(flattenBlocks(GlslPreprocessor.tokenize(String.join("\n", source.lines))));
		List<Decl> decls = new ArrayList<>();
		Set<String> functions = new HashSet<>();
		String localSize = null;
		Map<String, Integer> buffers = new LinkedHashMap<>();
		Map<String, int[]> structs = new HashMap<>();

		for (int[] item : items(tokens)) {
			List<Integer> sig = significant(tokens, item[0], item[1]);
			if (sig.isEmpty()) continue;
			String first = tokens.get(sig.get(0)).text();
			if (first.equals("struct")) {
				struct(tokens, sig, structs);
				continue;
			}
			if (first.equals("precision")) {
				blank(tokens, item[0], item[1]);
				continue;
			}
			// A compute shader's layout(local_size_x = 8, ...) in; goes back in at the top.
			if (stage == Stage.COMPUTE && first.equals("layout") && isInputLayout(tokens, sig)) {
				localSize = text(tokens, sig, 0, sig.size());
				blank(tokens, item[0], item[1]);
				continue;
			}
			if (bufferBlock(tokens, sig, buffers)) continue;
			String function = functionName(tokens, sig);
			if (function != null) {
				functions.add(function);
				continue;
			}
			List<Decl> found = declaration(tokens, sig);
			if (found != null) {
				decls.addAll(found);
				blank(tokens, item[0], item[1]);
			}
		}

		Set<String> idents = new HashSet<>();
		for (Token token : tokens) if (token.kind() == Kind.IDENT) idents.add(token.text());
		Parsed parsed = new Parsed(stage, version, gpuShader4, tokens, decls, idents, functions, source.origins, source.extensions);
		parsed.localSize = localSize;
		parsed.buffers.putAll(buffers);
		parsed.structs.putAll(structs);
		parsed.fragDataMax = maxIndex(tokens, "gl_FragData", parsed);
		if (idents.contains("gl_FragColor")) parsed.fragDataMax = Math.max(parsed.fragDataMax, 0);
		parsed.texCoordMax = maxIndex(tokens, "gl_TexCoord", parsed);
		return parsed;
	}

	/**
	 * Varyings in blocks ({@code out VertexData { vec4 color; flat int blockId; } vOut;}, Shrimple's) become one plain varying
	 * per member, named after the block and the member: the stages name the block the same, their instances not
	 * ({@code vOut}, {@code vIn}). {@code vOut.color} becomes that name. Arrays of blocks (geometry and tessellation stages)
	 * stay as they are.
	 */
	private static List<Token> flattenBlocks(List<Token> tokens) {
		// Where a block is: its item's start, to the item's end and the declarations in its place.
		Map<Integer, Integer> ends = new HashMap<>();
		Map<Integer, String> replacements = new HashMap<>();
		// Instance name to its members' new names.
		Map<String, Map<String, String>> instances = new HashMap<>();
		for (int[] item : items(tokens)) {
			List<Integer> sig = significant(tokens, item[0], item[1]);
			int k = 0;
			StringBuilder outer = new StringBuilder();
			String storage = null;
			while (k < sig.size()) {
				String s = tokens.get(sig.get(k)).text();
				if (s.equals("layout")) {
					while (k < sig.size() && !tokens.get(sig.get(k)).text().equals(")")) k++;
					k++;
					continue;
				}
				if (!QUALIFIERS.contains(s)) break;
				if (STORAGE.contains(s)) storage = s;
				outer.append(s).append(' ');
				k++;
			}
			if (!"in".equals(storage) && !"out".equals(storage) && !"varying".equals(storage)) continue;
			if (k + 1 >= sig.size() || tokens.get(sig.get(k)).kind() != Kind.IDENT || !tokens.get(sig.get(k + 1)).text().equals("{")) continue;
			String block = tokens.get(sig.get(k)).text();
			int close = k + 2;
			while (close < sig.size() && !tokens.get(sig.get(close)).text().equals("}")) close++;
			int after = close + 1;
			String instance = null;
			if (after < sig.size() && tokens.get(sig.get(after)).kind() == Kind.IDENT) instance = tokens.get(sig.get(after++)).text();
			if (after != sig.size() - 1 || !tokens.get(sig.get(after)).text().equals(";")) continue;

			Map<String, String> names = new HashMap<>();
			StringBuilder declarations = new StringBuilder();
			int m = k + 2;
			while (m < close) {
				int end = m;
				while (end < close && !tokens.get(sig.get(end)).text().equals(";")) end++;
				int type = m;
				while (type < end && QUALIFIERS.contains(tokens.get(sig.get(type)).text())) type++;
				StringBuilder member = new StringBuilder(outer);
				int depth = 0;
				for (int j = m; j < end; j++) {
					Token t = tokens.get(sig.get(j));
					String s = t.text();
					if (s.equals("[")) depth++;
					else if (s.equals("]")) depth--;
					String previous = j > m ? tokens.get(sig.get(j - 1)).text() : "";
					boolean name = depth == 0 && t.kind() == Kind.IDENT && j > type && (j == type + 1 || previous.equals(","));
					if (name) {
						String flat = instance != null ? block + "_" + s : s;
						names.put(s, flat);
						s = flat;
					}
					member.append(s).append(' ');
				}
				if (end > type) declarations.append(member).append("; ");
				m = end + 1;
			}
			// The item's leading space stays (it may end a line before it); its own lines are kept as empty ones.
			int start = sig.get(0);
			long breaks = 0;
			for (int i = start; i < item[1]; i++) breaks += tokens.get(i).text().chars().filter(c -> c == '\n').count();
			ends.put(start, item[1]);
			replacements.put(start, declarations + "\n".repeat((int) breaks));
			if (instance != null) instances.put(instance, names);
		}
		if (ends.isEmpty()) return new ArrayList<>(tokens);

		List<Token> out = new ArrayList<>(tokens.size());
		for (int i = 0; i < tokens.size(); i++) {
			Integer end = ends.get(i);
			if (end != null) {
				out.addAll(GlslPreprocessor.tokenize(replacements.get(i)));
				i = end - 1;
				continue;
			}
			Token t = tokens.get(i);
			Map<String, String> members = t.kind() == Kind.IDENT ? instances.get(t.text()) : null;
			int dot = members != null ? next(tokens, i) : -1;
			int field = dot >= 0 && tokens.get(dot).text().equals(".") ? next(tokens, dot) : -1;
			String flat = field >= 0 ? members.get(tokens.get(field).text()) : null;
			if (flat == null) {
				out.add(t);
				continue;
			}
			out.add(new Token(Kind.IDENT, flat));
			long breaks = 0;
			for (int j = i + 1; j < field; j++) breaks += tokens.get(j).text().chars().filter(c -> c == '\n').count();
			if (breaks > 0) out.add(new Token(Kind.SPACE, "\n".repeat((int) breaks)));
			i = field;
		}
		return out;
	}

	/**
	 * Constant two-dimensional arrays ({@code const float k[2][2] = {{...}, {...}};}, Kappa's) become one-dimensional: the game's
	 * GLSL 330 has no arrays of arrays, and SPIRV-Cross can't write their constructors. {@code k[i][j]} becomes
	 * {@code k[(i) * 2 + (j)]}. One whose rows are used alone stays as it is.
	 */
	private static List<Token> flattenArrays(List<Token> tokens) {
		// Name to {columns, the declaration's first token, the item's end}, and the declaration that replaces it.
		Map<String, int[]> arrays = new HashMap<>();
		Map<String, String> replacements = new HashMap<>();
		for (int[] item : items(tokens)) {
			List<Integer> sig = significant(tokens, item[0], item[1]);
			if (sig.size() < 12 || !tokens.get(sig.get(0)).text().equals("const")) continue;
			int k = 1;
			while (k < sig.size() && QUALIFIERS.contains(tokens.get(sig.get(k)).text())) k++;
			if (k + 9 >= sig.size()) continue;
			String type = tokens.get(sig.get(k)).text();
			String name = tokens.get(sig.get(k + 1)).text();
			if (!is(tokens, sig, k + 2, "[") || !is(tokens, sig, k + 4, "]") || !is(tokens, sig, k + 5, "[") || !is(tokens, sig, k + 7, "]")
					|| !is(tokens, sig, k + 8, "=") || !is(tokens, sig, sig.size() - 1, ";")) continue;
			int rows = parseInt(tokens.get(sig.get(k + 3)).text(), -1);
			int columns = parseInt(tokens.get(sig.get(k + 6)).text(), -1);
			List<String> values = new ArrayList<>();
			if (rows <= 0 || columns <= 0 || !elements(tokens, sig, k + 9, sig.size() - 1, 0, values) || values.size() != rows * columns) continue;
			int n = rows * columns;
			arrays.put(name, new int[] {columns, sig.get(0), item[1]});
			long breaks = 0;
			for (int i = sig.get(0); i < item[1]; i++) breaks += tokens.get(i).text().chars().filter(c -> c == '\n').count();
			replacements.put(name, "const " + type + " " + name + "[" + n + "] = " + type + "[" + n + "](" + String.join(", ", values) + ");"
				+ "\n".repeat((int) breaks));
		}
		if (arrays.isEmpty()) return tokens;
		// Only the ones always read [i][j].
		for (int i = 0; i < tokens.size(); i++) {
			Token t = tokens.get(i);
			int[] array = t.kind() == Kind.IDENT ? arrays.get(t.text()) : null;
			if (array == null || i >= array[1] && i < array[2]) continue;
			int open = next(tokens, i);
			int close = open >= 0 && tokens.get(open).text().equals("[") ? matching(tokens, open) : -1;
			int second = close >= 0 ? next(tokens, close) : -1;
			if (second < 0 || !tokens.get(second).text().equals("[")) arrays.remove(t.text());
		}
		if (arrays.isEmpty()) return tokens;
		// The declarations kept: their first token to {their end, the one-dimensional one}.
		Map<Integer, Map.Entry<Integer, String>> declarations = new HashMap<>();
		arrays.forEach((name, array) -> declarations.put(array[1], Map.entry(array[2], replacements.get(name))));
		List<Token> out = new ArrayList<>(tokens.size());
		rewriteIndices(tokens, 0, tokens.size(), arrays, declarations, out);
		return out;
	}

	private static void rewriteIndices(List<Token> tokens, int from, int to, Map<String, int[]> arrays,
			Map<Integer, Map.Entry<Integer, String>> declarations, List<Token> out) {
		for (int i = from; i < to; i++) {
			Map.Entry<Integer, String> declaration = declarations.get(i);
			if (declaration != null) {
				out.addAll(GlslPreprocessor.tokenize(declaration.getValue()));
				i = declaration.getKey() - 1;
				continue;
			}
			Token t = tokens.get(i);
			int[] array = t.kind() == Kind.IDENT ? arrays.get(t.text()) : null;
			if (array == null) {
				out.add(t);
				continue;
			}
			int open = next(tokens, i);
			int close = matching(tokens, open);
			int open2 = next(tokens, close);
			int close2 = matching(tokens, open2);
			out.add(t);
			out.addAll(GlslPreprocessor.tokenize("[("));
			rewriteIndices(tokens, open + 1, close, arrays, declarations, out);
			out.addAll(GlslPreprocessor.tokenize(") * " + array[0] + " + ("));
			rewriteIndices(tokens, open2 + 1, close2, arrays, declarations, out);
			out.addAll(GlslPreprocessor.tokenize(")]"));
			long breaks = 0;
			for (int j = i + 1; j <= close2; j++) if (j < open + 1 || j >= close && j < open2 + 1 || j >= close2) breaks += tokens.get(j).text().chars().filter(c -> c == '\n').count();
			if (breaks > 0) out.add(new Token(Kind.SPACE, "\n".repeat((int) breaks)));
			i = close2;
		}
	}

	/** The index of the bracket closing the one at {@code open}. */
	private static int matching(List<Token> tokens, int open) {
		int depth = 0;
		for (int j = open; j < tokens.size(); j++) {
			String s = tokens.get(j).text();
			if (s.equals("[") || s.equals("(") || s.equals("{")) depth++;
			else if ((s.equals("]") || s.equals(")") || s.equals("}")) && --depth == 0) return j;
		}
		return -1;
	}

	private static boolean is(List<Token> tokens, List<Integer> sig, int k, String text) {
		return k >= 0 && k < sig.size() && tokens.get(sig.get(k)).text().equals(text);
	}

	/**
	 * The values of an array's initializer ({@code {{a, b}, {c, d}}} or {@code float[2][2](float[2](a, b), ...)}), its innermost
	 * elements in order, into {@code values}; false if it isn't one of those.
	 */
	private static boolean elements(List<Token> tokens, List<Integer> sig, int from, int to, int depth, List<String> values) {
		if (from >= to) return false;
		String first = tokens.get(sig.get(from)).text();
		int open;
		if (first.equals("{")) {
			open = from;
		} else if (tokens.get(sig.get(from)).kind() == Kind.IDENT && is(tokens, sig, from + 1, "[")) {
			// A constructor: the type and its sizes, then its arguments.
			open = from + 1;
			while (open < to && !tokens.get(sig.get(open)).text().equals("(")) open++;
		} else {
			if (depth < 2) return false;
			values.add(text(tokens, sig, from, to));
			return true;
		}
		if (depth >= 2 || open >= to || !is(tokens, sig, to - 1, first.equals("{") ? "}" : ")")) return false;
		int start = open + 1;
		int nesting = 0;
		for (int j = open + 1; j < to - 1; j++) {
			String s = tokens.get(sig.get(j)).text();
			if (s.equals("(") || s.equals("[") || s.equals("{")) nesting++;
			else if (s.equals(")") || s.equals("]") || s.equals("}")) nesting--;
			else if (s.equals(",") && nesting == 0) {
				if (!elements(tokens, sig, start, j, depth + 1, values)) return false;
				start = j + 1;
			}
		}
		// A trailing comma ({a, b,}) leaves nothing after it.
		return start >= to - 1 || elements(tokens, sig, start, to - 1, depth + 1, values);
	}

	/** Whether the stage declares a variable, varying or parameter of this name (in its code: a type right before it). */
	private static boolean declares(Parsed p, String name) {
		for (Decl d : p.decls) if (d.name.equals(name)) return true;
		String before = null;
		for (Token t : p.body) {
			if (t.kind() == Kind.SPACE) continue;
			if (t.kind() == Kind.IDENT && t.text().equals(name) && before != null && (TYPE.matcher(before).matches() || p.structs.containsKey(before))) {
				return true;
			}
			before = t.kind() == Kind.IDENT ? t.text() : null;
		}
		return false;
	}

	private static final java.util.regex.Pattern TYPE = java.util.regex.Pattern.compile(
		"void|bool|int|uint|float|double|[biud]?vec[234]|d?mat[234](x[234])?|[iu]?sampler\\w*|[iu]?image\\w*");

	/** Whether an item is {@code layout(...) in;}: a stage's input layout (a compute shader's work group size, ...). */
	private static boolean isInputLayout(List<Token> tokens, List<Integer> sig) {
		int close = 1;
		while (close < sig.size() && !tokens.get(sig.get(close)).text().equals(")")) close++;
		return close + 3 == sig.size() && tokens.get(sig.get(close + 1)).text().equals("in") && tokens.get(sig.get(close + 2)).text().equals(";");
	}

	/**
	 * Whether an item is a storage buffer block ({@code layout(std430, binding = 0) buffer Name { ... };}), which stays in the
	 * code as it is; its name and binding go into {@code buffers}.
	 */
	private static boolean bufferBlock(List<Token> tokens, List<Integer> sig, Map<String, Integer> buffers) {
		int binding = -1;
		for (int k = 0; k < sig.size(); k++) {
			String s = tokens.get(sig.get(k)).text();
			if (s.equals("{") || s.equals("(") && k > 0 && !tokens.get(sig.get(k - 1)).text().equals("layout")) return false;
			if (s.equals("binding") && k + 2 < sig.size() && tokens.get(sig.get(k + 1)).text().equals("=")) {
				binding = parseInt(tokens.get(sig.get(k + 2)).text(), -1);
			}
			if (s.equals("buffer") && k + 1 < sig.size() && tokens.get(sig.get(k + 1)).kind() == Kind.IDENT) {
				buffers.put(tokens.get(sig.get(k + 1)).text(), binding);
				return true;
			}
		}
		return false;
	}

	/**
	 * A struct definition ({@code struct Name { vec3 a; float b[2]; };}) into {@code structs}: the index of its ';' and how many
	 * varying locations one takes (packs pass structs between stages: Photon's fog parameters).
	 */
	private static void struct(List<Token> tokens, List<Integer> sig, Map<String, int[]> structs) {
		if (sig.size() < 5 || tokens.get(sig.get(1)).kind() != Kind.IDENT || !tokens.get(sig.get(2)).text().equals("{")) return;
		int locations = 0;
		int k = 3;
		while (k < sig.size() && !tokens.get(sig.get(k)).text().equals("}")) {
			while (k < sig.size() && QUALIFIERS.contains(tokens.get(sig.get(k)).text())) k++;
			if (k >= sig.size()) break;
			String type = tokens.get(sig.get(k++)).text();
			int[] nested = structs.get(type);
			int[] mat = UniformLayout.matrix(type);
			int each = nested != null ? nested[1] : mat != null ? mat[0] : 1;
			while (k < sig.size()) {
				Token t = tokens.get(sig.get(k));
				if (t.text().equals(";") || t.text().equals("}")) {
					if (t.text().equals(";")) k++;
					break;
				}
				if (t.text().equals("[")) {
					int close = k;
					while (close < sig.size() && !tokens.get(sig.get(close)).text().equals("]")) close++;
					locations += each * (Math.max(1, parseInt(text(tokens, sig, k + 1, close), 1)) - 1);
					k = close + 1;
					continue;
				}
				if (t.kind() == Kind.IDENT) locations += each;
				k++;
			}
		}
		structs.put(tokens.get(sig.get(1)).text(), new int[] {sig.get(sig.size() - 1), Math.max(1, locations)});
	}

	/** Top-level items as [start, end) token ranges: declarations up to ';', function definitions up to their '}'. */
	private static List<int[]> items(List<Token> tokens) {
		List<int[]> out = new ArrayList<>();
		int start = 0;
		int parens = 0;
		int braces = 0;
		String last = "";
		for (int i = 0; i < tokens.size(); i++) {
			Token t = tokens.get(i);
			if (t.kind() == Kind.SPACE) continue;
			String s = t.text();
			if (s.equals("(") || s.equals("[")) {
				parens++;
			} else if (s.equals(")") || s.equals("]")) {
				parens--;
			} else if (s.equals("{")) {
				if (parens == 0 && braces == 0 && last.equals(")")) {
					int depth = 0;
					int j = i;
					for (; j < tokens.size(); j++) {
						String u = tokens.get(j).text();
						if (u.equals("{")) depth++;
						else if (u.equals("}") && --depth == 0) break;
					}
					out.add(new int[] {start, Math.min(j + 1, tokens.size())});
					start = j + 1;
					i = j;
					last = "}";
					continue;
				}
				braces++;
			} else if (s.equals("}")) {
				braces--;
			} else if (s.equals(";") && parens == 0 && braces == 0) {
				out.add(new int[] {start, i + 1});
				start = i + 1;
			}
			last = s;
		}
		if (start < tokens.size()) out.add(new int[] {start, tokens.size()});
		return out;
	}

	private static List<Integer> significant(List<Token> tokens, int start, int end) {
		List<Integer> out = new ArrayList<>();
		for (int i = start; i < end; i++) if (tokens.get(i).kind() != Kind.SPACE) out.add(i);
		return out;
	}

	/** Replaces a range with spaces, keeping its line breaks. */
	private static void blank(List<Token> tokens, int start, int end) {
		for (int i = start; i < end; i++) {
			String text = tokens.get(i).text();
			long breaks = text.chars().filter(c -> c == '\n').count();
			tokens.set(i, new Token(Kind.SPACE, breaks > 0 ? "\n".repeat((int) breaks) : (i == start ? " " : "")));
		}
	}

	/** The name of a function an item defines or declares, or null. */
	private static @Nullable String functionName(List<Token> tokens, List<Integer> sig) {
		for (int k = 0; k < sig.size(); k++) {
			String s = tokens.get(sig.get(k)).text();
			if (s.equals("=") || s.equals("{") || s.equals("[")) return null;
			if (s.equals("(")) {
				if (k < 2) return null;
				Token name = tokens.get(sig.get(k - 1));
				Token type = tokens.get(sig.get(k - 2));
				if (name.kind() != Kind.IDENT || (type.kind() != Kind.IDENT && !type.text().equals("]"))) return null;
				if (STORAGE.contains(tokens.get(sig.get(0)).text()) || tokens.get(sig.get(0)).text().equals("layout")) return null;
				return name.text();
			}
		}
		return null;
	}

	/** The declarations of an item with a storage qualifier, or null if it has none. */
	private static @Nullable List<Decl> declaration(List<Token> tokens, List<Integer> sig) throws TranslateException {
		int k = 0;
		String storage = null;
		boolean flat = false;
		boolean noperspective = false;
		int location = -1;
		String format = null;
		StringBuilder memory = new StringBuilder();
		while (k < sig.size()) {
			String s = tokens.get(sig.get(k)).text();
			if (s.equals("layout")) {
				int close = k + 1;
				while (close < sig.size() && !tokens.get(sig.get(close)).text().equals(")")) close++;
				StringBuilder inside = new StringBuilder();
				for (int j = k + 2; j < close; j++) inside.append(tokens.get(sig.get(j)).text());
				for (String part : inside.toString().split(",")) {
					String[] kv = part.split("=");
					if (kv.length == 2 && kv[0].strip().equals("location")) location = parseInt(kv[1].strip(), -1);
					else if (kv.length == 1 && !part.isBlank() && !BLOCK_LAYOUTS.contains(part.strip())) format = part.strip();
				}
				k = close + 1;
				continue;
			}
			if (!QUALIFIERS.contains(s)) break;
			if (STORAGE.contains(s)) storage = s;
			if (s.equals("flat")) flat = true;
			if (s.equals("noperspective")) noperspective = true;
			if (MEMORY.contains(s)) memory.append(s).append(' ');
			k++;
		}
		if (storage == null) return null;
		if (k >= sig.size()) return List.of();
		for (int j = k; j < sig.size(); j++) {
			if (tokens.get(sig.get(j)).text().equals("{")) {
				throw new TranslateException("Interface blocks aren't supported yet: " + text(tokens, sig, k, sig.size()));
			}
		}
		String type = tokens.get(sig.get(k++)).text();
		int typeArray = 0;
		if (k < sig.size() && tokens.get(sig.get(k)).text().equals("[")) {
			int close = indexOf(tokens, sig, k, "]");
			typeArray = parseInt(text(tokens, sig, k + 1, close), 0);
			k = close + 1;
		}
		List<Decl> out = new ArrayList<>();
		while (k < sig.size()) {
			Token nameToken = tokens.get(sig.get(k++));
			if (nameToken.kind() != Kind.IDENT) {
				if (nameToken.text().equals(";")) break;
				throw new TranslateException("Can't read declaration " + text(tokens, sig, 0, sig.size()));
			}
			int array = typeArray;
			if (k < sig.size() && tokens.get(sig.get(k)).text().equals("[")) {
				int close = indexOf(tokens, sig, k, "]");
				String size = text(tokens, sig, k + 1, close);
				array = parseInt(size, -1);
				if (array < 0) throw new TranslateException("Array size must be a number: " + nameToken.text() + "[" + size + "]");
				k = close + 1;
			}
			String init = null;
			if (k < sig.size() && tokens.get(sig.get(k)).text().equals("=")) {
				int depth = 0;
				int j = k + 1;
				for (; j < sig.size(); j++) {
					String s = tokens.get(sig.get(j)).text();
					if (s.equals("(") || s.equals("[")) depth++;
					else if (s.equals(")") || s.equals("]")) depth--;
					else if ((s.equals(",") || s.equals(";")) && depth == 0) break;
				}
				init = text(tokens, sig, k + 1, j);
				k = j;
			}
			String qualifiers = type.contains("image") ? (format != null ? "layout(" + format + ") " : "") + memory : "";
			out.add(new Decl(storage, flat, noperspective, type, nameToken.text(), array, init, location, qualifiers));
			if (k < sig.size() && tokens.get(sig.get(k)).text().equals(",")) {
				k++;
				location = -1;
				continue;
			}
			break;
		}
		return out;
	}

	private static int indexOf(List<Token> tokens, List<Integer> sig, int from, String what) throws TranslateException {
		for (int j = from; j < sig.size(); j++) if (tokens.get(sig.get(j)).text().equals(what)) return j;
		throw new TranslateException("Missing '" + what + "' in " + text(tokens, sig, 0, sig.size()));
	}

	private static String text(List<Token> tokens, List<Integer> sig, int from, int to) {
		StringBuilder out = new StringBuilder();
		for (int j = from; j < to && j < sig.size(); j++) {
			Token t = tokens.get(sig.get(j));
			if (!out.isEmpty() && t.kind() != Kind.OTHER && out.charAt(out.length() - 1) != '(') out.append(' ');
			out.append(t.text());
		}
		return out.toString();
	}

	private static int parseInt(String text, int fallback) {
		try {
			String t = text.strip().replaceAll("[uU]$", "");
			return t.startsWith("0x") ? Integer.parseInt(t.substring(2), 16) : Integer.parseInt(t);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** The highest literal index of {@code name[...]}; 7 if it's indexed with anything else. */
	private static int maxIndex(List<Token> tokens, String name, Parsed parsed) {
		int max = -1;
		for (int i = 0; i < tokens.size(); i++) {
			if (tokens.get(i).kind() != Kind.IDENT || !tokens.get(i).text().equals(name)) continue;
			int open = next(tokens, i);
			if (open < 0 || !tokens.get(open).text().equals("[")) continue;
			int value = next(tokens, open);
			int close = value < 0 ? -1 : next(tokens, value);
			int index = close >= 0 && tokens.get(close).text().equals("]") ? parseInt(tokens.get(value).text(), -1) : -1;
			if (index < 0) {
				parsed.warnings.add(name + " indexed with a variable");
				index = 7;
			}
			max = Math.max(max, index);
		}
		return max;
	}

	/** The arguments of the call whose '(' is at {@code open}, each as its significant tokens' text. */
	private static List<String> arguments(List<Token> tokens, int open) {
		List<String> out = new ArrayList<>();
		StringBuilder arg = new StringBuilder();
		int depth = 0;
		for (int k = open; k >= 0 && k < tokens.size(); k = next(tokens, k)) {
			String s = tokens.get(k).text();
			if (s.equals("(") || s.equals("[")) {
				if (depth++ == 0) continue;
			} else if (s.equals(")") || s.equals("]")) {
				if (--depth == 0) break;
			} else if (s.equals(",") && depth == 1) {
				out.add(arg.toString());
				arg.setLength(0);
				continue;
			}
			arg.append(s);
		}
		if (!arg.isEmpty() || !out.isEmpty()) out.add(arg.toString());
		return out;
	}

	private static int next(List<Token> tokens, int i) {
		for (int j = i + 1; j < tokens.size(); j++) if (tokens.get(j).kind() != Kind.SPACE) return j;
		return -1;
	}

	// ---- Uniforms ----

	/** Adds a stage's plain uniforms to the pack's layout. Call for every program before {@link UniformLayout#finish()}. */
	public static void collectUniforms(Parsed parsed, UniformLayout layout) {
		// gl_Fog where the game's Fog block isn't bound (composite, final...) reads these.
		if (parsed.idents.contains("gl_Fog")) {
			layout.add("ab_fogColor", "vec3", 0);
			for (String name : List.of("ab_fogStart", "ab_fogEnd", "ab_fogDensity")) layout.add(name, "float", 0);
		}
		for (Decl decl : parsed.decls) {
			if (!decl.storage.equals("uniform") || isOpaque(decl.type) || UNIFORM_ALIASES.containsKey(decl.name) || decl.init != null) continue;
			if (!UniformLayout.isKnownType(decl.type)) {
				parsed.warnings.add("Uniform of unsupported type " + decl.type + " " + decl.name);
				continue;
			}
			layout.add(rename(decl.name), decl.type, decl.arraySize);
		}
	}

	private static boolean isOpaque(String type) {
		return type.contains("sampler") || type.contains("image");
	}

	private static String rename(String name) {
		return RESERVED.contains(name) ? name + "_ab" : name;
	}

	/** The sampler a pack name means in this kind of program, before {@link #samplerGlslName}. */
	public static String canonicalSampler(String name, TranslateTarget.Kind kind, boolean waterShadow) {
		switch (name) {
			case "texture", "tex", "gtexture":
				return "gtexture";
			case "shadow":
				return waterShadow ? "shadowtex1" : "shadowtex0";
			case "watershadow", "shadowtex0HW":
				return "shadowtex0";
			case "shadowtex1HW":
				return "shadowtex1";
			case "shadowcolor":
				return "shadowcolor0";
			case "gaux1":
				return "colortex4";
			case "gaux2":
				return "colortex5";
			case "gaux3":
				return "colortex6";
			case "gaux4":
				return "colortex7";
			default:
				break;
		}
		if (kind == TranslateTarget.Kind.FULLSCREEN) {
			switch (name) {
				case "gcolor":
					return "colortex0";
				case "gdepth":
					return "colortex1";
				case "gnormal":
					return "colortex2";
				case "composite":
					return "colortex3";
				case "gdepthtex":
					return "depthtex0";
				default:
					break;
			}
		}
		return name;
	}

	/** The GLSL name a sampler gets: the atlas and lightmap use the game's names, so the game's bindings reach them. */
	public static String samplerGlslName(String canonical) {
		return switch (canonical) {
			case "gtexture" -> "Sampler0";
			case "lightmap" -> "Sampler2";
			default -> canonical;
		};
	}

	// ---- Translation ----

	/**
	 * Translates a program. {@code drawBuffers} are the buffers its DRAWBUFFERS/RENDERTARGETS directive names, or null for the
	 * default (one per output). {@code layout} must be finished.
	 */
	public static Program translate(Parsed vs, Parsed fs, int @Nullable [] drawBuffers, TranslateTarget target, UniformLayout layout)
			throws TranslateException {
		List<String> warnings = new ArrayList<>();
		warnings.addAll(vs.warnings);
		warnings.addAll(fs.warnings);

		// Varyings: the union of what the vertex stage writes and the fragment stage reads, the same locations in both.
		Map<String, Decl> varyingDecls = new TreeMap<>();
		for (Decl d : vs.decls) {
			if (d.storage.equals("varying") || d.storage.equals("out")) varyingDecls.put(d.name, d);
		}
		for (Decl d : fs.decls) {
			if (!(d.storage.equals("varying") || d.storage.equals("in"))) continue;
			Decl old = varyingDecls.get(d.name);
			if (old == null) {
				varyingDecls.put(d.name, d);
			} else {
				if (!old.type.equals(d.type) || old.arraySize != d.arraySize) {
					warnings.add("Varying " + d.name + " is " + old.type + " in the vertex shader but " + d.type + " in the fragment shader");
				}
				if (d.flat && !old.flat) varyingDecls.put(d.name, new Decl(old.storage, true, old.noperspective, old.type, old.name, old.arraySize, null, -1, ""));
			}
		}
		int texCoords = Math.max(vs.texCoordMax, fs.texCoordMax) + 1;
		if (vs.idents.contains("gl_FrontColor") || fs.idents.contains("gl_Color")) addBuiltinVarying(varyingDecls, "ab_FrontColor", "vec4", 0);
		if (vs.idents.contains("gl_FrontSecondaryColor") || fs.idents.contains("gl_SecondaryColor")) {
			addBuiltinVarying(varyingDecls, "ab_FrontSecondaryColor", "vec4", 0);
		}
		if (vs.idents.contains("gl_FogFragCoord") || fs.idents.contains("gl_FogFragCoord")) addBuiltinVarying(varyingDecls, "ab_FogFragCoord", "float", 0);
		if (texCoords > 0) addBuiltinVarying(varyingDecls, "ab_TexCoord", "vec4", texCoords);

		// A varying named like another program's uniform (Bliss's sunColor) is renamed, as the stages' code names it (see emit).
		Set<String> blockMembers = new HashSet<>();
		for (UniformLayout.Member m : layout.members()) blockMembers.add(m.glslName());
		List<Varying> varyings = new ArrayList<>();
		int location = 0;
		for (Decl d : varyingDecls.values()) {
			String type = d.type;
			boolean integer = type.startsWith("int") || type.startsWith("ivec") || type.startsWith("uint") || type.startsWith("uvec");
			String name = rename(d.name);
			varyings.add(new Varying(blockMembers.contains(name) ? name + "_ab" : name, type, d.arraySize, d.flat || integer, d.noperspective && !d.flat, location));
			int[] mat = UniformLayout.matrix(type);
			int[] struct = vs.structs.getOrDefault(type, fs.structs.get(type));
			location += (struct != null ? struct[1] : mat != null ? mat[0] : 1) * Math.max(1, d.arraySize);
		}

		// Fragment outputs.
		Map<Integer, String> outputNames = new TreeMap<>();
		Map<Integer, String> outputTypes = new HashMap<>();
		for (int i = 0; i <= fs.fragDataMax; i++) {
			outputNames.put(i, "ab_FragData" + i);
			outputTypes.put(i, "vec4");
		}
		int order = 0;
		for (Decl d : fs.decls) {
			if (!d.storage.equals("out")) continue;
			int index = d.location >= 0 ? d.location : d.name.matches("outColor[0-9]+") ? Integer.parseInt(d.name.substring(8)) : order;
			order++;
			outputNames.put(index, rename(d.name));
			outputTypes.put(index, d.type);
		}
		int outputCount = outputNames.isEmpty() ? 0 : ((TreeMap<Integer, String>) outputNames).lastKey() + 1;
		int[] buffers = drawBuffers;
		if (buffers == null) {
			buffers = new int[outputCount];
			for (int i = 0; i < outputCount; i++) buffers[i] = i;
		}
		int[] written = new int[outputCount];
		Map<Integer, Integer> outputLocations = new HashMap<>();
		for (int i = 0; i < outputCount; i++) {
			int buffer = i < buffers.length ? buffers[i] : -1;
			int loc = -1;
			if (buffer >= 0) {
				if (target.attachments == null) {
					loc = i;
				} else {
					for (int a = 0; a < target.attachments.length; a++) if (target.attachments[a] == buffer) loc = a;
				}
			}
			if (buffer >= 0 && loc < 0) warnings.add("Output " + i + " writes buffer " + buffer + ", which this pass doesn't have");
			written[i] = loc >= 0 ? buffer : -1;
			outputLocations.put(i, loc);
		}

		boolean waterShadow = false;
		for (Decl d : vs.decls) waterShadow |= d.storage.equals("uniform") && d.name.equals("watershadow");
		for (Decl d : fs.decls) waterShadow |= d.storage.equals("uniform") && d.name.equals("watershadow");

		Set<String> samplers = new LinkedHashSet<>();
		Set<String> images = new LinkedHashSet<>();
		Set<String> blocks = new LinkedHashSet<>();
		StageOut vertex = emit(vs, target, layout, varyings, Map.of(), Map.of(), Map.of(), waterShadow, samplers, images, blocks, warnings);
		StageOut fragment = emit(fs, target, layout, varyings, outputNames, outputTypes, outputLocations, waterShadow, samplers, images, blocks,
			warnings);
		Map<String, Integer> storage = new LinkedHashMap<>(vs.buffers);
		storage.putAll(fs.buffers);
		return new Program(vertex.text, fragment.text, vertex.header, fragment.header, vs.origins, fs.origins, samplers, images, storage, blocks,
			written, varyings, warnings);
	}

	/** Translates a compute program (a .csh file). {@code layout} must be finished. */
	public static Compute translateCompute(Parsed cs, UniformLayout layout) throws TranslateException {
		if (cs.stage != Stage.COMPUTE) throw new IllegalArgumentException("Not a compute stage");
		if (cs.localSize == null) throw new TranslateException("The compute shader has no layout(local_size_x = ...) in;");
		List<String> warnings = new ArrayList<>(cs.warnings);
		Set<String> samplers = new LinkedHashSet<>();
		Set<String> images = new LinkedHashSet<>();
		StageOut out = emit(cs, TranslateTarget.fullscreen(null), layout, List.of(), Map.of(), Map.of(), Map.of(), false, samplers, images,
			new LinkedHashSet<>(), warnings);
		return new Compute(out.text, out.header, cs.origins, samplers, images, cs.buffers, warnings);
	}

	private static void addBuiltinVarying(Map<String, Decl> varyings, String name, String type, int array) {
		varyings.putIfAbsent(name, new Decl("varying", false, false, type, name, array, null, -1, ""));
	}

	private record StageOut(String text, int header) {}

	/**
	 * The sampler parameters of the pack's functions, which count in their function only: a shadow one (sampler2DShadow tex) is
	 * compared where the function samples it, and a plain one named like a shadow sampler isn't. A function the pack has for both
	 * kinds of sampler is overloaded: its shadow definitions (by their name's token) are renamed.
	 */
	private record SamplerScopes(Map<String, List<int[]>> shadow, Map<String, List<int[]>> plain, Set<Integer> shadowDefinitions,
			Set<String> overloaded) {
		static SamplerScopes of(List<Token> body) {
			Map<String, List<int[]>> shadow = new HashMap<>();
			Map<String, List<int[]>> plain = new HashMap<>();
			// Function name to the kinds of its definitions with a sampler parameter: 1 shadow, 2 plain.
			Map<String, Integer> kinds = new HashMap<>();
			Set<Integer> definitions = new HashSet<>();
			for (int[] item : items(body)) {
				List<Integer> sig = significant(body, item[0], item[1]);
				if (sig.size() < 4 || !body.get(sig.get(sig.size() - 1)).text().equals("}")) continue;
				int open = 0;
				while (open < sig.size() && !body.get(sig.get(open)).text().equals("(")) open++;
				if (open == 0 || open >= sig.size() || body.get(sig.get(open - 1)).kind() != Kind.IDENT) continue;
				int close = open;
				for (int depth = 0; close < sig.size(); close++) {
					String s = body.get(sig.get(close)).text();
					if (s.equals("(")) depth++;
					else if (s.equals(")") && --depth == 0) break;
				}
				if (close + 1 >= sig.size() || !body.get(sig.get(close + 1)).text().equals("{")) continue;
				int[] range = {sig.get(close + 1), item[1]};
				int kind = 0;
				for (int k = open + 1; k + 1 < close; k++) {
					Token type = body.get(sig.get(k));
					Token name = body.get(sig.get(k + 1));
					if (type.kind() != Kind.IDENT || name.kind() != Kind.IDENT || !type.text().contains("sampler")) continue;
					boolean isShadow = SHADOW_TYPES.containsKey(type.text());
					(isShadow ? shadow : plain).computeIfAbsent(name.text(), n -> new ArrayList<>()).add(range);
					kind |= isShadow ? 1 : 2;
				}
				if (kind == 0) continue;
				int function = sig.get(open - 1);
				kinds.merge(body.get(function).text(), (kind & 1) != 0 ? 1 : 2, (a, b) -> a | b);
				if ((kind & 1) != 0) definitions.add(function);
			}
			Set<String> overloaded = new HashSet<>();
			kinds.forEach((function, kind) -> {
				if (kind == 3) overloaded.add(function);
			});
			definitions.removeIf(d -> !overloaded.contains(body.get(d).text()));
			return new SamplerScopes(shadow, plain, definitions, overloaded);
		}

		/** Whether a sampler name at a token is a shadow sampler: a parameter of the function it's in, else a declared one. */
		boolean isShadow(String name, int at, Set<String> declared) {
			if (in(this.plain, name, at)) return false;
			return in(this.shadow, name, at) || declared.contains(name);
		}

		/** Whether the call whose name is at {@code at} has a shadow sampler (a lone name) for an argument. */
		boolean shadowArgument(List<Token> tokens, int at, Set<String> declared) {
			int open = next(tokens, at);
			if (open < 0 || !tokens.get(open).text().equals("(")) return false;
			int depth = 0;
			String before = "(";
			for (int k = open; k >= 0 && k < tokens.size(); k = next(tokens, k)) {
				String s = tokens.get(k).text();
				if (s.equals("(") || s.equals("[")) depth++;
				else if ((s.equals(")") || s.equals("]")) && --depth == 0) return false;
				if (depth == 1 && tokens.get(k).kind() == Kind.IDENT && (before.equals("(") || before.equals(","))) {
					int after = next(tokens, k);
					String a = after < 0 ? "" : tokens.get(after).text();
					if ((a.equals(",") || a.equals(")")) && this.isShadow(s, k, declared)) return true;
				}
				if (k != open) before = s;
			}
			return false;
		}

		private static boolean in(Map<String, List<int[]>> scopes, String name, int at) {
			List<int[]> ranges = scopes.get(name);
			if (ranges == null) return false;
			for (int[] r : ranges) if (at >= r[0] && at < r[1]) return true;
			return false;
		}
	}

	/** A replacement for a built-in variable: our global, its type, and how ab_setup fills it (null: a varying or output). */
	private record Builtin(String name, String declaration, @Nullable String setup) {}

	private static StageOut emit(Parsed p, TranslateTarget target, UniformLayout layout, List<Varying> varyings, Map<Integer, String> outputNames,
			Map<Integer, String> outputTypes, Map<Integer, Integer> outputLocations, boolean waterShadow, Set<String> allSamplers,
			Set<String> allImages, Set<String> allBlocks, List<String> warnings) throws TranslateException {
		boolean vertex = p.stage == Stage.VERTEX;
		boolean compute = p.stage == Stage.COMPUTE;
		String where = p.stage.name().toLowerCase(Locale.ROOT);

		// Names to change in the pack's code.
		Map<String, String> renames = new HashMap<>();
		for (String name : RESERVED) renames.put(name, name + "_ab");
		if (p.version < 130) {
			for (String word : NEW_KEYWORDS) {
				if (p.gpuShader4 && GPU_SHADER4_KEYWORDS.contains(word) || NEW_TYPES.contains(word) && usedAsType(p.body, word)) continue;
				renames.put(word, "ab_kw_" + word);
			}
		}
		for (String function : p.functions) {
			if (p.version < 130 && NEW_FUNCTIONS.contains(function) || p.version < 400 && FUNCTIONS_400.contains(function)
					|| p.version < 420 && FUNCTIONS_420.contains(function)) {
				renames.put(function, "ab_fn_" + function);
			}
		}
		for (String word : VULKAN_KEYWORDS) renames.put(word, "ab_kw_" + word);
		// Shadow sampler types become plain ones; the pack's functions taking one ("float shadowAt(sampler2DShadow tex, vec3 pos)")
		// compare in the shader where they sample it (see SamplerScopes).
		for (Token t : p.body) {
			if (t.kind() == Kind.IDENT && SHADOW_TYPES.containsKey(t.text())) renames.put(t.text(), SHADOW_TYPES.get(t.text()));
		}
		SamplerScopes scopes = SamplerScopes.of(p.body);
		boolean textureIsVariable = p.version < 130 && p.idents.contains("texture");
		renames.put("main", "ab_packMain");

		// Declarations.
		StringBuilder decls = new StringBuilder();
		Set<String> shadowSamplers = new HashSet<>();
		Map<String, String> samplerTypes = new LinkedHashMap<>();
		Map<String, String> images = new LinkedHashMap<>();
		List<String[]> attributes = new ArrayList<>();
		List<String[]> aliases = new ArrayList<>();
		StringBuilder globals = new StringBuilder();
		for (Decl d : withImplicit(p)) {
			String name = rename(d.name);
			switch (d.storage) {
				case "uniform" -> {
					if (isOpaque(d.type) && !d.type.contains("sampler")) {
						// An image (the pack's custom images): declared as written; its unit is set by name (ExternalBindings).
						images.put(name, d.qualifiers + "uniform " + d.type + " " + name + array(d.arraySize) + ";\n");
						if (!name.equals(d.name)) renames.put(d.name, name);
					} else if (isOpaque(d.type)) {
						String canonical = canonicalSampler(d.name, target.kind, waterShadow);
						String glsl = samplerGlslName(canonical);
						renames.put(d.name, glsl);
						String type = d.type;
						if (type.endsWith("Shadow")) {
							type = type.substring(0, type.length() - "Shadow".length());
							// By the pack's name: Bloop samples "shadow" compared and shadowtex0 (the same texture) as it is.
							shadowSamplers.add(d.name);
						}
						String old = samplerTypes.putIfAbsent(glsl, type);
						if (old != null && !old.equals(type)) throw new TranslateException("Sampler " + glsl + " declared as " + old + " and " + type);
					} else if (UNIFORM_ALIASES.containsKey(d.name)) {
						aliases.add(new String[] {d.type, d.name, target.values.getOrDefault(d.name, UNIFORM_ALIASES.get(d.name))});
					} else if (d.init != null) {
						warnings.add("Uniform " + d.name + " has a default value; it's used as a constant");
						globals.append(d.type).append(' ').append(name).append(array(d.arraySize)).append(" = ").append(d.init).append(";\n");
					} else if (UniformLayout.isKnownType(d.type)) {
						String glsl = layout.glslName(name, d.type);
						if (layout.member(glsl) == null) throw new TranslateException("Uniform " + d.name + " missing from the layout");
						if (!glsl.equals(name)) renames.put(d.name, glsl);
						else if (!name.equals(d.name)) renames.put(d.name, name);
					}
				}
				case "attribute", "in" -> {
					if (vertex) {
						attributes.add(new String[] {d.type, name, d.name, array(d.arraySize)});
						if (!name.equals(d.name)) renames.put(d.name, name);
					}
				}
				default -> {
				}
			}
		}
		if (textureIsVariable && !renames.containsKey("texture")) renames.put("texture", "ab_var_texture");
		// The uniform blocks have every program's uniforms: one this stage doesn't declare may be a name it has for something else
		// (Complementary's dh_terrain makes gbufferProjection a variable), renamed in it. One it uses without declaring it at all
		// is the uniform (Shrimple's hand reads currentRenderedItemId, declared only by its entities).
		Set<String> uniforms = new HashSet<>();
		for (Decl d : withImplicit(p)) if (d.storage.equals("uniform")) uniforms.add(d.name);
		for (UniformLayout.Member m : layout.members()) {
			if (!uniforms.contains(m.name()) && p.idents.contains(m.glslName()) && declares(p, m.glslName())) {
				renames.putIfAbsent(m.glslName(), m.glslName() + "_ab");
			}
		}

		// Varyings of a struct type are declared right after the struct (in the pack's code), the others in the header.
		Map<Integer, String> inserts = new HashMap<>();
		for (Varying v : varyings) {
			int[] struct = p.structs.get(v.type);
			if (struct != null) inserts.merge(struct[0], " " + varying(v, vertex), String::concat);
		}

		// Built-ins this stage uses.
		Map<String, Builtin> builtins = builtins(p, target, outputNames);
		Set<String> used = new LinkedHashSet<>();
		List<Token> body = rewrite(p, renames, builtins, outputNames, shadowSamplers, scopes, inserts, used);
		for (String[] alias : aliases) used.addAll(dependencies(alias[2]));

		// ab_setup: matrices first, then what's built on them, then attributes.
		StringBuilder setup = new StringBuilder();
		StringBuilder builtinGlobals = new StringBuilder();
		Set<String> done = new HashSet<>();
		for (String name : List.copyOf(used)) addBuiltin(name, builtins, used, done, setup, builtinGlobals);
		for (String[] a : attributes) {
			String value = target.attributes.get(a[2]);
			if (value == null) value = ATTRIBUTE_DEFAULTS.getOrDefault(a[2], "vec4(0.0)");
			globals.append(a[0]).append(' ').append(a[1]).append(a[3]).append(";\n");
			if (a[3].isEmpty()) setup.append('\t').append(a[1]).append(" = ").append(a[0]).append('(').append(value).append(");\n");
			for (String dep : dependencies(value)) addBuiltin(dep, builtins, used, done, setup, builtinGlobals);
		}
		for (String[] alias : aliases) {
			globals.append(alias[0]).append(' ').append(alias[1]).append(";\n");
			setup.append('\t').append(alias[1]).append(" = ").append(alias[0]).append('(').append(alias[2]).append(");\n");
		}
		if (vertex) for (String dep : dependencies(target.epilogue)) addBuiltin(dep, builtins, used, done, setup, builtinGlobals);

		// The header.
		// The pack's version where it's newer: its integer conversions, bitfield functions, memory qualifiers, ...
		int version = Math.min(Math.max(p.version, compute ? 430 : 330), 460);
		StringBuilder header = new StringBuilder(compute ? "#version " + version + " core\n"
			: "#version " + version + "\n#extension GL_ARB_separate_shader_objects : require\n");
		Set<String> extensions = new LinkedHashSet<>();
		for (GlslPreprocessor.Extension e : p.extensions) {
			if (Set.of("GL_ARB_shader_texture_lod", "GL_EXT_gpu_shader4", "GL_ARB_explicit_attrib_location", "GL_ARB_separate_shader_objects",
					"GL_ARB_gpu_shader4", "GL_EXT_gpu_shader4_1").contains(e.name())) {
				continue;
			}
			extensions.add(e.name());
		}
		if (!compute) {
			// What images and storage buffers need in version 330; layout(binding = N) is allowed by 420pack (the binding the
			// program ends up with is set by name, see ExternalBindings).
			// (Storage buffers' memory qualifiers, readonly and the rest, are the image extension's words.)
			if (!images.isEmpty() || !p.buffers.isEmpty() && version < 420) extensions.add("GL_ARB_shader_image_load_store");
			if (!p.buffers.isEmpty() && version < 430) extensions.add("GL_ARB_shader_storage_buffer_object");
			if (p.idents.contains("binding")) extensions.add("GL_ARB_shading_language_420pack");
		}
		for (String e : extensions) header.append("#extension ").append(e).append(" : enable\n");
		String epilogue = vertex ? target.epilogue : "";
		String generated = setup + "\n" + builtinGlobals + globals + epilogue;
		if (generated.contains("ab_ToGl")) header.append(TO_GL);
		Set<String> generatedIdents = identifiers(generated);
		Set<String> declared = new HashSet<>();
		for (String block : target.blocks) {
			List<String> members = TranslateTarget.GAME_BLOCKS.get(block);
			boolean needed = false;
			for (String member : members) {
				String name = member.substring(member.indexOf(' ') + 1);
				needed |= generatedIdents.contains(name) && !declared.contains(name);
			}
			if (!needed) continue;
			header.append("layout(std140) uniform ").append(block).append(" {\n");
			for (String member : members) {
				header.append('\t').append(member).append(";\n");
				declared.add(member.substring(member.indexOf(' ') + 1));
			}
			header.append("};\n");
			allBlocks.add(block);
		}
		header.append(layout.declaration(false)).append(layout.declaration(true));
		allBlocks.add(UniformLayout.FRAME_BLOCK);
		allBlocks.add(UniformLayout.DRAW_BLOCK);
		for (Map.Entry<String, String> sampler : samplerTypes.entrySet()) {
			header.append("uniform ").append(sampler.getValue()).append(' ').append(sampler.getKey()).append(";\n");
			allSamplers.add(sampler.getKey());
		}
		for (String image : images.values()) header.append(image);
		allImages.addAll(images.keySet());
		if (compute) header.append(p.localSize).append('\n');
		if (vertex) {
			int input = 0;
			for (Map.Entry<String, String> in : target.inputs.entrySet()) {
				if (!generatedIdents.contains(in.getKey())) continue;
				header.append("layout(location = ").append(input++).append(") in ").append(in.getValue()).append(' ').append(in.getKey()).append(";\n");
			}
		}
		for (Varying v : varyings) {
			if (!p.structs.containsKey(v.type)) header.append(varying(v, vertex)).append('\n');
		}
		if (!vertex) {
			for (Map.Entry<Integer, String> output : outputNames.entrySet()) {
				int loc = outputLocations.getOrDefault(output.getKey(), -1);
				String type = outputTypes.getOrDefault(output.getKey(), "vec4");
				if (loc >= 0) header.append("layout(location = ").append(loc).append(") out ");
				header.append(type).append(' ').append(output.getValue()).append(";\n");
			}
		}
		header.append(builtinGlobals).append(globals);
		if (used.stream().anyMatch(u -> u.startsWith("ab_shadow"))) header.append(SHADOW_HELPERS);
		if (used.stream().anyMatch(u -> u.startsWith("ab_shadowTextureGather"))) header.append(SHADOW_GATHER);
		if (used.contains("ab_textureGather") || used.contains("ab_textureGatherOffset")) header.append(GATHER);
		if (used.contains("ab_dhHasTexture") || used.contains("ab_dhSampleTexture")) header.append(DH_TEXTURE);

		StringBuilder out = new StringBuilder(header);
		int headerLines = (int) out.chars().filter(c -> c == '\n').count();
		out.append(GlslPreprocessor.join(body)).append('\n');
		if (vertex && setup.indexOf("ab_unoct") >= 0) out.append(UNOCT);
		out.append("void ab_setup() {\n").append(setup).append("}\n");
		out.append("void main() {\n\tab_setup();\n\tab_packMain();\n").append(epilogue);
		String keeps = !vertex && target.alphaTest != null && "vec4".equals(outputTypes.get(0)) && outputNames.containsKey(0)
			? target.alphaTest.keeps(outputNames.get(0) + ".a") : null;
		if (keeps != null) out.append("\tif (!(").append(keeps).append(")) discard;\n");
		if (vertex) {
			out.append("#ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE\n\tgl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n#endif\n");
		}
		out.append("}\n");
		if (!p.idents.contains("main")) throw new TranslateException("The " + where + " shader has no main()");
		return new StageOut(out.toString(), headerLines);
	}

	private static String varying(Varying v, boolean vertex) {
		return "layout(location = " + v.location + ") " + (v.flat ? "flat " : v.noperspective ? "noperspective " : "") + (vertex ? "out " : "in ") + v.type
			+ " " + v.name + array(v.arraySize) + ";";
	}

	/** Whether code uses a word as a type: followed by a name ("uvec3 x") or as a constructor ("uvec3(...)"). */
	private static boolean usedAsType(List<Token> body, String word) {
		for (int i = 0; i < body.size(); i++) {
			if (body.get(i).kind() != Kind.IDENT || !body.get(i).text().equals(word)) continue;
			int next = next(body, i);
			if (next >= 0 && (body.get(next).kind() == Kind.IDENT || body.get(next).text().equals("("))) return true;
		}
		return false;
	}

	/** The stage's declarations, plus {@link #IMPLICIT_UNIFORMS} and {@link #IMPLICIT_ATTRIBUTES} it uses but doesn't declare. */
	private static List<Decl> withImplicit(Parsed p) {
		Set<String> declared = new HashSet<>(p.functions);
		for (Decl d : p.decls) declared.add(d.name);
		List<Decl> out = new ArrayList<>(p.decls);
		for (Map.Entry<String, String> e : IMPLICIT_UNIFORMS.entrySet()) {
			if (p.idents.contains(e.getKey()) && !declared.contains(e.getKey())) out.add(new Decl("uniform", false, false, e.getValue(), e.getKey(), 0, null, -1, ""));
		}
		if (p.stage == Stage.VERTEX) {
			for (Map.Entry<String, String> e : IMPLICIT_ATTRIBUTES.entrySet()) {
				if (p.idents.contains(e.getKey()) && !declared.contains(e.getKey())) out.add(new Decl("in", false, false, e.getValue(), e.getKey(), 0, null, -1, ""));
			}
		}
		return out;
	}

	private static String array(int size) {
		return size > 0 ? "[" + size + "]" : "";
	}

	/** The built-in variables a stage can use, by their GL names. */
	private static Map<String, Builtin> builtins(Parsed p, TranslateTarget target, Map<Integer, String> outputNames) {
		Map<String, Builtin> b = new HashMap<>();
		b.put("gl_ModelViewMatrix", new Builtin("ab_ModelViewMatrix", "mat4", target.modelViewMatrix));
		b.put("gl_ProjectionMatrix", new Builtin("ab_ProjectionMatrix", "mat4", target.projectionMatrix));
		b.put("gl_ModelViewProjectionMatrix", new Builtin("ab_ModelViewProjectionMatrix", "mat4", "ab_ProjectionMatrix * ab_ModelViewMatrix"));
		b.put("gl_NormalMatrix", new Builtin("ab_NormalMatrix", "mat3", "mat3(ab_ModelViewMatrix)"));
		for (String m : List.of("ModelView", "Projection", "ModelViewProjection")) {
			String base = "ab_" + m + "Matrix";
			b.put("gl_" + m + "MatrixInverse", new Builtin(base + "Inverse", "mat4", "inverse(" + base + ")"));
			b.put("gl_" + m + "MatrixTranspose", new Builtin(base + "Transpose", "mat4", "transpose(" + base + ")"));
			b.put("gl_" + m + "MatrixInverseTranspose", new Builtin(base + "InverseTranspose", "mat4", "transpose(inverse(" + base + "))"));
		}
		b.put("gl_TextureMatrix", new Builtin("ab_TextureMatrix", "mat4[3]",
			"ab_TextureMatrix[0] = " + target.textureMatrix + ";\n\tab_TextureMatrix[1] = " + LIGHTMAP_MATRIX + ";\n\tab_TextureMatrix[2] = ab_TextureMatrix[1]"));
		b.put("gl_Fog", new Builtin("ab_Fog", "ab_FogParameters", target.blocks.contains("Fog")
			? "ab_Fog = ab_FogParameters(FogColor, 1.0, FogRenderDistanceStart, FogRenderDistanceEnd, 1.0 / max(FogRenderDistanceEnd - FogRenderDistanceStart, 0.0001))"
			: "ab_Fog = ab_FogParameters(vec4(ab_fogColor, 1.0), ab_fogDensity, ab_fogStart, ab_fogEnd, 1.0 / max(ab_fogEnd - ab_fogStart, 0.0001))"));
		b.put("gl_FogFragCoord", new Builtin("ab_FogFragCoord", "", null));
		b.put("gl_TexCoord", new Builtin("ab_TexCoord", "", null));
		if (p.stage == Stage.VERTEX) {
			for (String attribute : List.of("gl_Vertex", "gl_Color", "gl_Normal", "gl_SecondaryColor", "gl_FogCoord", "gl_MultiTexCoord0",
					"gl_MultiTexCoord1", "gl_MultiTexCoord2", "gl_MultiTexCoord3", "gl_MultiTexCoord4", "gl_MultiTexCoord5", "gl_MultiTexCoord6",
					"gl_MultiTexCoord7")) {
				String type = attribute.equals("gl_Normal") ? "vec3" : attribute.equals("gl_FogCoord") ? "float" : "vec4";
				String value = target.attributes.get(attribute);
				if (value == null) value = ATTRIBUTE_DEFAULTS.getOrDefault(attribute, "vec4(0.0, 0.0, 0.0, 1.0)");
				b.put(attribute, new Builtin("ab_" + attribute.substring(3), type, type + "(" + value + ")"));
			}
			b.put("gl_FrontColor", new Builtin("ab_FrontColor", "", null));
			b.put("gl_FrontSecondaryColor", new Builtin("ab_FrontSecondaryColor", "", null));
			b.put("gl_BackColor", new Builtin("ab_BackColor", "vec4", ""));
			b.put("gl_BackSecondaryColor", new Builtin("ab_BackSecondaryColor", "vec4", ""));
			b.put("gl_ClipVertex", new Builtin("ab_ClipVertex", "vec4", ""));
			b.put("gl_VertexID", new Builtin("gl_VertexIndex", "", null));
			b.put("gl_InstanceID", new Builtin("gl_InstanceIndex", "", null));
		} else if (p.stage == Stage.FRAGMENT) {
			b.put("gl_Color", new Builtin("ab_FrontColor", "", null));
			b.put("gl_SecondaryColor", new Builtin("ab_FrontSecondaryColor", "", null));
		}
		return b;
	}

	private static void addBuiltin(String name, Map<String, Builtin> builtins, Set<String> used, Set<String> done, StringBuilder setup,
			StringBuilder globals) {
		Builtin builtin = null;
		for (Builtin candidate : builtins.values()) {
			if (candidate.name.equals(name)) builtin = candidate;
		}
		if (builtin == null || builtin.setup == null || !done.add(name)) return;
		for (String dep : dependencies(builtin.setup)) {
			if (!dep.equals(name)) addBuiltin(dep, builtins, used, done, setup, globals);
		}
		used.add(name);
		if (builtin.declaration.equals("ab_FogParameters")) {
			globals.append("struct ab_FogParameters { vec4 color; float density; float start; float end; float scale; };\n");
		}
		String declaration = builtin.declaration;
		if (declaration.endsWith("]")) {
			int open = declaration.indexOf('[');
			globals.append(declaration, 0, open).append(' ').append(name).append(declaration.substring(open)).append(";\n");
		} else {
			globals.append(declaration).append(' ').append(name).append(";\n");
		}
		if (builtin.setup.isEmpty()) return;
		if (builtin.setup.startsWith(name)) setup.append('\t').append(builtin.setup).append(";\n");
		else setup.append('\t').append(name).append(" = ").append(builtin.setup).append(";\n");
	}

	/** The ab_ names an expression uses. */
	private static Set<String> dependencies(String expression) {
		Set<String> out = new LinkedHashSet<>();
		for (String ident : identifiers(expression)) if (ident.startsWith("ab_")) out.add(ident);
		return out;
	}

	private static Set<String> identifiers(String text) {
		Set<String> out = new HashSet<>();
		for (Token t : GlslPreprocessor.tokenize(text)) if (t.kind() == Kind.IDENT) out.add(t.text());
		return out;
	}

	/** The pack's code with names changed and built-ins replaced; adds the ab_ names it needs to {@code used}. */
	private static List<Token> rewrite(Parsed p, Map<String, String> renames, Map<String, Builtin> builtins, Map<Integer, String> outputNames,
			Set<String> shadowSamplers, SamplerScopes scopes, Map<Integer, String> inserts, Set<String> used) throws TranslateException {
		List<Token> in = p.body;
		List<Token> out = new ArrayList<>(in.size());
		for (int i = 0; i < in.size(); i++) {
			Token t = in.get(i);
			if (t.kind() != Kind.IDENT) {
				out.add(t);
				String insert = inserts.get(i);
				if (insert != null) out.add(new Token(Kind.OTHER, insert));
				continue;
			}
			String name = t.text();
			if (p.stage == Stage.FRAGMENT && (name.equals("gl_FragData") || name.equals("gl_FragColor"))) {
				int index = 0;
				if (name.equals("gl_FragData")) {
					int open = next(in, i);
					int value = open < 0 ? -1 : next(in, open);
					int close = value < 0 ? -1 : next(in, value);
					index = close >= 0 && in.get(open).text().equals("[") && in.get(close).text().equals("]") ? parseInt(in.get(value).text(), -1) : -1;
					if (index < 0) throw new TranslateException("gl_FragData must be indexed with a number");
					i = close;
				}
				out.add(new Token(Kind.IDENT, outputNames.get(index)));
				continue;
			}
			if (name.equals("ftransform")) {
				int open = next(in, i);
				int close = open < 0 ? -1 : next(in, open);
				if (close >= 0 && in.get(open).text().equals("(") && in.get(close).text().equals(")")) {
					used.add("ab_ModelViewProjectionMatrix");
					used.add("ab_Vertex");
					out.add(new Token(Kind.OTHER, "(ab_ModelViewProjectionMatrix * ab_Vertex)"));
					i = close;
					continue;
				}
			}
			// texture(shadowSampler, ...) in newer packs: compare in the shader.
			if (name.equals("texture") || name.equals("textureLod") || name.equals("textureProj") || name.equals("textureGather")
					|| name.equals("textureGatherOffset")) {
				int open = next(in, i);
				int arg = open < 0 ? -1 : next(in, open);
				if (arg >= 0 && in.get(open).text().equals("(") && in.get(arg).kind() == Kind.IDENT) {
					if (scopes.isShadow(in.get(arg).text(), arg, shadowSamplers)) {
						String helper = "ab_shadow" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
						used.add(helper);
						out.add(new Token(Kind.IDENT, helper));
						continue;
					}
				}
				// A gather of another component than red (Hysteria's depth in .b): the game's GLSL 330 can't, so it's fetched texel by texel.
				if (name.startsWith("textureGather") && open >= 0 && in.get(open).text().equals("(")) {
					List<String> args = arguments(in, open);
					if (args.size() == (name.equals("textureGather") ? 3 : 4) && !args.get(args.size() - 1).equals("0")) {
						String helper = "ab_" + name;
						used.add(helper);
						out.add(new Token(Kind.IDENT, helper));
						continue;
					}
				}
			}
			// The shadow one of a function the pack has for both kinds of sampler (BSL Classic's texture2DShadow): its own name, as
			// both take sampler2D now. Calls with a shadow sampler for an argument go to it.
			if (scopes.overloaded().contains(name) && (scopes.shadowDefinitions().contains(i) || scopes.shadowArgument(in, i, shadowSamplers))) {
				out.add(new Token(Kind.IDENT, name + "_abShadow"));
				continue;
			}
			String renamed = renames.get(name);
			// A 120 pack may call texture() (drivers allow it) while also naming a sampler or variable "texture".
			if (renamed != null && name.equals("texture") && !renamed.startsWith("ab_fn_")) {
				int open = next(in, i);
				if (open >= 0 && in.get(open).text().equals("(")) renamed = null;
			}
			if (renamed != null) {
				out.add(new Token(Kind.IDENT, renamed));
				continue;
			}
			String dh = DH_FUNCTIONS.get(name);
			if (dh != null && !p.functions.contains(name)) {
				used.add(dh);
				out.add(new Token(Kind.IDENT, dh));
				continue;
			}
			String function = FUNCTIONS.get(name);
			if (function != null) {
				if (function.startsWith("ab_")) used.add(function);
				out.add(new Token(Kind.IDENT, function));
				continue;
			}
			Builtin builtin = builtins.get(name);
			if (builtin != null) {
				used.add(builtin.name);
				out.add(new Token(Kind.IDENT, builtin.name));
				continue;
			}
			if (name.startsWith("gl_") && !KNOWN_GL.contains(name)) {
				throw new TranslateException("Unsupported built-in " + name + " in the " + p.stage.name().toLowerCase(Locale.ROOT) + " shader");
			}
			out.add(t);
		}
		return out;
	}

	/** Built-ins GLSL 330 still has. */
	private static final Set<String> KNOWN_GL = Set.of(
		"gl_Position", "gl_PointSize", "gl_ClipDistance", "gl_FragCoord", "gl_FrontFacing", "gl_PointCoord", "gl_FragDepth",
		"gl_PrimitiveID", "gl_DepthRange", "gl_Layer", "gl_SampleID", "gl_SamplePosition", "gl_VertexIndex", "gl_InstanceIndex", "gl_MaxDrawBuffers", "gl_MaxTextureImageUnits", "gl_MaxVertexAttribs",
		"gl_GlobalInvocationID", "gl_LocalInvocationID", "gl_WorkGroupID", "gl_NumWorkGroups", "gl_LocalInvocationIndex", "gl_WorkGroupSize");
}
