package com.afterburner.labs.shaderpack;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a translated program will be drawn with: the vertex inputs it gets, the game's uniform blocks bound when it draws,
 * where its matrices come from, and which buffers the pass has as color attachments.
 * <p>
 * Expressions are GLSL and may use the vertex inputs, members of the target's uniform blocks (ModelViewMat, ProjMat,
 * TextureMat, ColorModulator, ModelOffset, ChunkPosition, ...) and {@code ab_ToGl}, which turns the game's reversed-depth
 * projection into a normal OpenGL one.
 */
public final class TranslateTarget {
	public enum Kind {
		/** gbuffers_* programs: world geometry into the pack's buffers. */
		GBUFFERS,
		/** shadow: world geometry into the shadow map. */
		SHADOW,
		/** prepare, deferred, composite, final, shadowcomp: one triangle over the screen. */
		FULLSCREEN
	}

	/** The game's uniform blocks, as its shaders declare them: block name to members. */
	public static final Map<String, List<String>> GAME_BLOCKS = new LinkedHashMap<>();

	static {
		GAME_BLOCKS.put("DynamicTransforms", List.of("mat4 ModelViewMat", "mat4 TextureMat", "vec4 ColorModulator", "vec3 ModelOffset"));
		GAME_BLOCKS.put("TerrainUniform", List.of("mat4 ModelViewMat", "ivec2 TextureSize"));
		GAME_BLOCKS.put("ChunkSection", List.of("ivec3 ChunkPosition", "float ChunkVisibility"));
		GAME_BLOCKS.put("Projection", List.of("mat4 ProjMat"));
		GAME_BLOCKS.put("Globals", List.of("ivec3 CameraBlockPos", "float GlintAlpha", "vec3 CameraOffset", "float GameTime", "vec2 ScreenSize",
			"int MenuBlurRadius", "int UseRgss"));
		GAME_BLOCKS.put("Fog", List.of("vec4 FogColor", "float FogEnvironmentalStart", "float FogEnvironmentalEnd", "float FogRenderDistanceStart",
			"float FogRenderDistanceEnd", "float FogSkyEnd", "float FogCloudsEnd"));
		// Afterburner's far terrain (LodRenderer, far_terrain.vsh); LodView's projection is the one packs draw it with (dhProjection).
		GAME_BLOCKS.put("LodNode", List.of("ivec4 NodeOrigin", "ivec4 NodeFlags"));
		GAME_BLOCKS.put("LodView", List.of("ivec4 DrawnY", "mat4 DhProjection"));
	}

	/** A far terrain vertex's face (down, up, north, south, west, east; see {@link #world}) as its normal. */
	private static final String FAR_NORMAL = ("(F < 2 ? vec3(0.0, float(F) * 2.0 - 1.0, 0.0) "
		+ ": F < 4 ? vec3(0.0, 0.0, float(F) * 2.0 - 5.0) : vec3(float(F) * 2.0 - 9.0, 0.0, 0.0))").replace("F", "int((Position & 131071u) % 6u)");

	/** The blocks every world pass binds for all its draws (RenderSystem.bindDefaultUniforms). */
	public static final Set<String> PASS_BLOCKS = Set.of("Projection", "Globals", "Fog");

	/** OptiFine's depth scale for the hand (MC_HAND_DEPTH): its depth is squeezed around the middle, in front of the world. */
	public static final String HAND_DEPTH = "0.125";

	public final Kind kind;
	/** The vertex format's elements: name to GLSL type ("Position" to "vec3"). */
	public final Map<String, String> inputs;
	/** The game's uniform blocks the program may use (see {@link #GAME_BLOCKS}), in declaration order. */
	public final Set<String> blocks;
	/** Attribute name (gl_Vertex, vaPosition, mc_Entity, ...) to its value; missing ones get defaults. */
	public final Map<String, String> attributes;
	public final String modelViewMatrix;
	public final String projectionMatrix;
	public final String textureMatrix;
	/** Values of OptiFine's newer uniforms that are the game's own (colorModulator, chunkOffset, ...). */
	public final Map<String, String> values;
	/** Vertex shader code run after the pack's main, before the depth remap (line widening, the hand's depth). */
	public final String epilogue;
	/** Buffer index of each color attachment of the pass, in order; null means the program's own draw buffers. */
	public final int @Nullable [] attachments;
	/** The alpha test old programs rely on (OpenGL's fixed one, which OptiFine and Iris keep), or null for none. */
	public final @Nullable AlphaTest alphaTest;

	private TranslateTarget(Kind kind, Map<String, String> inputs, Set<String> blocks, Map<String, String> attributes, String modelViewMatrix,
			String projectionMatrix, String textureMatrix, Map<String, String> values, String epilogue, int @Nullable [] attachments,
			@Nullable AlphaTest alphaTest) {
		this.kind = kind;
		this.inputs = inputs;
		this.blocks = blocks;
		this.attributes = attributes;
		this.modelViewMatrix = modelViewMatrix;
		this.projectionMatrix = projectionMatrix;
		this.textureMatrix = textureMatrix;
		this.values = values;
		this.epilogue = epilogue;
		this.attachments = attachments;
		this.alphaTest = alphaTest;
	}

	/** The same with an alpha test, whose reference alphaTestRef then is (as with Iris). */
	public TranslateTarget withAlphaTest(AlphaTest test) {
		Map<String, String> values = new LinkedHashMap<>(this.values);
		values.put("alphaTestRef", test.glslReference());
		return new TranslateTarget(this.kind, this.inputs, this.blocks, this.attributes, this.modelViewMatrix, this.projectionMatrix,
			this.textureMatrix, values, this.epilogue, this.attachments, test);
	}

	/**
	 * World geometry drawn by one of the game's pipelines. {@code inputs} are its vertex format's elements, {@code blocks} the
	 * uniform blocks bound when it draws (its own and {@link #PASS_BLOCKS}). Terrain is recognized by TerrainUniform; its
	 * 16-byte vertices (Afterburner's compact format) by an integer Position, and the extended format built for packs by its
	 * AbNormal; Afterburner's far terrain by LodNode, drawn with its own projection (Distant Horizons' dhProjection). {@code extra}
	 * adds or replaces attribute values.
	 */
	public static TranslateTarget world(Kind kind, Map<String, String> inputs, Collection<String> blocks, boolean hand, Map<String, String> extra,
			int @Nullable [] attachments) {
		Set<String> available = new LinkedHashSet<>();
		for (String block : GAME_BLOCKS.keySet()) if (blocks.contains(block)) available.add(block);
		boolean terrain = available.contains("TerrainUniform");
		// Multidraw terrain gets the section's position as instance attributes instead of a block.
		if (inputs.containsKey("ChunkPosition")) available.remove("ChunkSection");
		boolean dynamic = available.contains("DynamicTransforms");
		boolean compact = terrain && "uvec4".equals(inputs.get("Position"));
		boolean far = available.contains("LodNode") && "uint".equals(inputs.get("Position"));

		Map<String, String> a = new LinkedHashMap<>();
		Map<String, String> values = new LinkedHashMap<>();
		String color = inputs.containsKey("Color") ? "Color" : "vec4(1.0)";
		String uv2 = inputs.containsKey("UV2") ? "UV2" : "ivec2(240, 240)";
		String local = inputs.containsKey("Position") ? "Position" : "vec3(0.0)";
		String offset;
		if (far) {
			// See LodRenderer: a corner in voxels from its node's corner (x: bits 0-7, z: 8-15, y: 16-27), its face (28-30) and whether
			// it's water (31); a color with the light in the alpha (sky light in the high 4 bits, block light in the low ones). Not
			// shaded: packs light it.
			// See LodMesher: x, z and the face in bits 0-16 as (z * 129 + x) * 6 + face, the material in 17-19, y in 20-31.
			local = "(vec3(float((Position & 131071u) / 6u % 129u), float(Position >> 20u), float((Position & 131071u) / 774u)) * float(NodeOrigin.w))";
			offset = "(vec3(NodeOrigin.xyz - CameraBlockPos) + CameraOffset)";
			color = "vec4(Color.rgb, 1.0)";
			uv2 = "ivec2((int(round(Color.a * 255.0)) & 15) << 4, (int(round(Color.a * 255.0)) >> 4) << 4)";
		} else if (terrain) {
			offset = "(vec3(ChunkPosition - CameraBlockPos) + CameraOffset)";
			if (compact) {
				// See CompactVertices: 1/2048 blocks from 8 before the section's corner; the section's place in its region and the
				// block light in w; the sky light in the color's alpha.
				local = "(vec3(Position.xyz) * (1.0 / 2048.0) - 8.0 + vec3(float(Position.w & 7u), float((Position.w >> 3u) & 3u), "
					+ "float((Position.w >> 5u) & 7u)) * 16.0)";
				color = "vec4(Color.rgb, 1.0)";
				uv2 = "ivec2(int((Position.w >> 8u) & 255u), int(round(Color.a * 255.0)))";
			}
		} else {
			offset = dynamic ? "ModelOffset" : "vec3(0.0)";
		}
		a.put("gl_Vertex", "vec4(" + local + " + " + offset + ", 1.0)");
		a.put("vaPosition", local);
		a.put("gl_Color", dynamic ? "(" + color + " * ColorModulator)" : color);
		a.put("vaColor", color);
		String uv0 = inputs.containsKey("UV0") ? "UV0" : "vec2(0.0)";
		a.put("gl_MultiTexCoord0", "vec4(" + uv0 + ", 0.0, 1.0)");
		a.put("vaUV0", uv0);
		a.put("gl_MultiTexCoord1", "vec4(vec2(" + uv2 + "), 0.0, 1.0)");
		a.put("gl_MultiTexCoord2", "vec4(vec2(" + uv2 + "), 0.0, 1.0)");
		a.put("vaUV2", uv2);
		a.put("vaUV1", inputs.containsKey("UV1") ? "UV1" : "ivec2(0, 10)");
		String normal = far ? FAR_NORMAL : inputs.containsKey("Normal") ? "Normal.xyz" : "vec3(0.0, 1.0, 0.0)";
		a.put("gl_Normal", normal);
		a.put("vaNormal", normal);
		// Distant Horizons' material (DH_BLOCK_...) for the far vertex's material slot (LodKinds), 4 bits a slot: unknown 0, leaves 1,
		// lava 6, water 12, grass 13, illuminated 15, snow 8, sand 9.
		if (far) a.put("dhMaterialId", "int((0x98FDC610u >> (((Position >> 17u) & 7u) * 4u)) & 15u)");
		if (compact && inputs.containsKey("AbNormal") && inputs.containsKey("AbBlock") && inputs.containsKey("AbMidTex")) {
			// See CompactVertices.EXTENDED_FORMAT: the quad's normal and tangent, folded onto an octahedron; the middle of its
			// texture; the pack's block ID + 1 and flags (1: a fluid, 2: the tangent's handedness is negative) and the quad's block in
			// its section. Like Iris, the render type (mc_Entity.y) is 1 for fluids and -1 for blocks, the ID -1 for blocks the pack
			// doesn't name; at_midBlock is from the vertex to its block's middle, in 64ths of a block.
			a.put("gl_Normal", "ab_unoct(AbNormal.xy)");
			a.put("vaNormal", "ab_unoct(AbNormal.xy)");
			a.put("at_tangent", "vec4(ab_unoct(AbNormal.zw), (AbBlock.y & 2u) != 0u ? -1.0 : 1.0)");
			a.put("mc_midTexCoord", "vec4(AbMidTex, 0.0, 1.0)");
			a.put("mc_Entity", "vec4(float(AbBlock.x) - 1.0, (AbBlock.y & 1u) != 0u ? 1.0 : -1.0, 0.0, 1.0)");
			a.put("at_midBlock", "vec4((vec3(float((AbBlock.y >> 2u) & 15u), float((AbBlock.y >> 6u) & 15u), float((AbBlock.y >> 10u) & 15u)) + 0.5 "
				+ "- (vec3(Position.xyz) * (1.0 / 2048.0) - 8.0)) * 64.0, 0.0)");
		}
		a.putAll(extra);

		values.put("colorModulator", dynamic ? "ColorModulator" : "vec4(1.0)");
		values.put("modelOffset", offset);
		values.put("chunkOffset", offset);

		StringBuilder epilogue = new StringBuilder();
		if (inputs.containsKey("LineWidth") && inputs.containsKey("Normal") && available.contains("Globals")) {
			// The game draws lines as thin quads, each end twice, widened on screen by the vertex shader; so do we, from where
			// the pack put the vertex.
			epilogue.append("""
				\t{
				\t\tvec4 ab_lineEnd = ab_ProjectionMatrix * (ab_ModelViewMatrix * vec4(Position + Normal.xyz + AB_OFFSET, 1.0));
				\t\tvec3 ab_ndc1 = gl_Position.xyz / gl_Position.w;
				\t\tvec3 ab_ndc2 = ab_lineEnd.xyz / ab_lineEnd.w;
				\t\tvec2 ab_lineDir = normalize((ab_ndc2.xy - ab_ndc1.xy) * ScreenSize);
				\t\tvec2 ab_lineOffset = vec2(-ab_lineDir.y, ab_lineDir.x) * LineWidth / ScreenSize;
				\t\tif (ab_lineOffset.x < 0.0) ab_lineOffset = -ab_lineOffset;
				\t\tif (gl_VertexIndex % 2 != 0) ab_lineOffset = -ab_lineOffset;
				\t\tgl_Position = vec4((ab_ndc1 + vec3(ab_lineOffset, 0.0)) * gl_Position.w, gl_Position.w);
				\t}
				""".replace("AB_OFFSET", offset));
		}
		if (hand) epilogue.append("\tgl_Position.z *= ").append(HAND_DEPTH).append(";\n");

		String modelView = dynamic || terrain ? "ModelViewMat" : "mat4(1.0)";
		String projection = far && available.contains("LodView") ? "DhProjection"
			: !available.contains("Projection") ? "mat4(1.0)" : kind == Kind.SHADOW ? "ProjMat" : "(ab_ToGl * ProjMat)";
		if (kind == Kind.SHADOW && dynamic && !terrain) {
			modelView = "(" + SHADOW_FROM_VIEW + " * ModelViewMat)";
			projection = SHADOW_PROJECTION;
		}
		return new TranslateTarget(kind, inputs, available, a, modelView, projection, dynamic ? "TextureMat" : "mat4(1.0)", values, epilogue.toString(),
			attachments, null);
	}

	/**
	 * The shadow's view after the camera's view taken out, and its projection, in the pack's frame block: entities drawn into the
	 * shadow map ({@code ShadowMap#renderEntities}) come with the camera's view in ModelViewMat and its projection bound.
	 */
	public static final String SHADOW_FROM_VIEW = "ab_shadowFromView", SHADOW_PROJECTION = "ab_shadowProjection";

	/** The usual entity format and blocks, for checking packs offline. */
	public static TranslateTarget entity(Kind kind, int @Nullable [] attachments) {
		Set<String> blocks = new LinkedHashSet<>(PASS_BLOCKS);
		blocks.add("DynamicTransforms");
		return world(kind, entityInputs(), blocks, false, Map.of(), attachments);
	}

	/** One triangle covering the screen, drawn without a vertex buffer; positions and texture coordinates run 0 to 1 on screen. */
	public static TranslateTarget fullscreen(int @Nullable [] attachments) {
		Map<String, String> a = new LinkedHashMap<>();
		String uv = "vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2)";
		a.put("gl_Vertex", "vec4(" + uv + ", 0.0, 1.0)");
		a.put("vaPosition", "vec3(" + uv + ", 0.0)");
		a.put("gl_MultiTexCoord0", "vec4(" + uv + ", 0.0, 1.0)");
		a.put("vaUV0", uv);
		a.put("gl_Color", "vec4(1.0)");
		a.put("vaColor", "vec4(1.0)");
		a.put("gl_Normal", "vec3(0.0, 0.0, 1.0)");
		a.put("vaNormal", "vec3(0.0, 0.0, 1.0)");
		// glOrtho(0, 1, 0, 1, -1, 1).
		String ortho = "mat4(2.0, 0.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, -1.0, -1.0, 0.0, 1.0)";
		Map<String, String> values = Map.of("colorModulator", "vec4(1.0)", "modelOffset", "vec3(0.0)", "chunkOffset", "vec3(0.0)");
		return new TranslateTarget(Kind.FULLSCREEN, Map.of(), Set.of(), a, "mat4(1.0)", ortho, "mat4(1.0)", values, "", attachments, null);
	}

	/** The usual entity format (POSITION_COLOR_TEX_OVERLAY_LIGHT_NORMAL). */
	public static Map<String, String> entityInputs() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("Position", "vec3");
		m.put("Color", "vec4");
		m.put("UV0", "vec2");
		m.put("UV1", "ivec2");
		m.put("UV2", "ivec2");
		m.put("Normal", "vec3");
		return m;
	}
}
