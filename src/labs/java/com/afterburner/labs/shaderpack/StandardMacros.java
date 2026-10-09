package com.afterburner.labs.shaderpack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The macros OptiFine defines in every pack file (shaders.txt, "Standard Macros"). Where the machine can run Iris's extra
 * features (OpenGL 4.3), {@link #addIris} adds Iris's macros too, so packs turn on what needs them (colored lighting, ...);
 * otherwise packs see an OptiFine-style loader and IS_IRIS stays undefined.
 */
public final class StandardMacros {
	/** Same format as Iris for the new version scheme: "26.3" gives 260300. */
	public static final int MC_VERSION = 260300;
	/** What our translated shaders target, whatever the driver could do. */
	public static final int GL_VERSION = 330;
	/** The Iris version packs see with {@link #addIris}: 1.11.3, whose at_midBlock Complementary's colored lighting asks for. */
	public static final int IRIS_VERSION = 11103;

	/** The render stages, in the order OptiFine runs them; the "renderStage" uniform uses these numbers. */
	public static final String[] RENDER_STAGES = {
		"NONE", "SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON", "STARS", "VOID", "TERRAIN_SOLID", "TERRAIN_CUTOUT_MIPPED",
		"TERRAIN_CUTOUT", "ENTITIES", "BLOCK_ENTITIES", "DESTROY", "OUTLINE", "DEBUG", "HAND_SOLID", "TERRAIN_TRANSLUCENT",
		"TRIPWIRE", "PARTICLES", "CLOUDS", "RAIN_SNOW", "WORLD_BORDER", "HAND_TRANSLUCENT"
	};

	/** Extensions our translated shaders can use, offered to packs as MC_GL_... macros. */
	private static final String[] EXTENSIONS = {
		"GL_ARB_shader_texture_lod", "GL_EXT_gpu_shader4", "GL_ARB_explicit_attrib_location", "GL_ARB_separate_shader_objects"
	};

	private StandardMacros() {
	}

	/**
	 * The macros for this machine. {@code vendor} and {@code renderer} are the GL strings; {@code mipmapLevel} is the game's
	 * mipmap setting.
	 */
	public static Map<String, String> of(String vendor, String renderer, int mipmapLevel) {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("MC_VERSION", Integer.toString(MC_VERSION));
		m.put("MC_GL_VERSION", Integer.toString(GL_VERSION));
		m.put("MC_GLSL_VERSION", Integer.toString(GL_VERSION));

		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		m.put(os.contains("win") ? "MC_OS_WINDOWS" : os.contains("mac") ? "MC_OS_MAC" : os.contains("linux") ? "MC_OS_LINUX" : "MC_OS_OTHER", "");

		String v = vendor.toLowerCase(Locale.ROOT);
		String vendorMacro = v.startsWith("ati") ? "MC_GL_VENDOR_ATI"
			: v.contains("amd") ? "MC_GL_VENDOR_AMD"
			: v.contains("intel") ? "MC_GL_VENDOR_INTEL"
			: v.contains("nvidia") ? "MC_GL_VENDOR_NVIDIA"
			: v.contains("mesa") ? "MC_GL_VENDOR_MESA"
			: v.contains("x.org") ? "MC_GL_VENDOR_XORG"
			: "MC_GL_VENDOR_OTHER";
		m.put(vendorMacro, "");

		String r = renderer.toLowerCase(Locale.ROOT);
		String rendererMacro = r.contains("amd") || r.contains("ati") || r.contains("radeon") ? "MC_GL_RENDERER_RADEON"
			: r.contains("quadro") ? "MC_GL_RENDERER_QUADRO"
			: r.contains("geforce") || r.contains("nvidia") ? "MC_GL_RENDERER_GEFORCE"
			: r.contains("intel") ? "MC_GL_RENDERER_INTEL"
			: r.contains("gallium") ? "MC_GL_RENDERER_GALLIUM"
			: r.contains("mesa") ? "MC_GL_RENDERER_MESA"
			: "MC_GL_RENDERER_OTHER";
		m.put(rendererMacro, "");

		for (String extension : EXTENSIONS) m.put("MC_" + extension, "");

		// OptiFine's normal and specular map settings, on by default (Bliss counts on them); the maps packs read are flat and
		// blank (normals, specular: see PackTextures).
		m.put("MC_NORMAL_MAP", "");
		m.put("MC_SPECULAR_MAP", "");
		m.put("MC_RENDER_QUALITY", "1.0");
		m.put("MC_SHADOW_QUALITY", "1.0");
		m.put("MC_HAND_DEPTH", "0.125");
		m.put("MC_MIPMAP_LEVEL", Integer.toString(mipmapLevel));

		for (int i = 0; i < RENDER_STAGES.length; i++) m.put("MC_RENDER_STAGE_" + RENDER_STAGES[i], Integer.toString(i));
		return m;
	}

	/**
	 * Iris's macros, for machines that can run the Iris features we have: custom images, storage buffers and compute shaders
	 * (OpenGL 4.3, not macOS). Packs then also take their other Iris paths: the renderStage uniform to tell the sun from the
	 * moon and vanilla stars from the sky, cameraPositionInt/Fract, at_midBlock. Iris features we don't have (the block
	 * emission attribute, per-buffer blending, ...) stay undefined.
	 */
	public static void addIris(Map<String, String> m) {
		m.put("IS_IRIS", "");
		m.put("IRIS_VERSION", Integer.toString(IRIS_VERSION));
		m.put("IRIS_FEATURE_CUSTOM_IMAGES", "");
		m.put("IRIS_FEATURE_SSBO", "");
		m.put("IRIS_FEATURE_COMPUTE_SHADERS", "");
		m.put("IRIS_FEATURE_REVERSED_CULLING", "");
		// The game sorts translucent terrain back to front, as Iris does.
		m.put("IRIS_HAS_TRANSLUCENCY_SORTING", "");
	}

	/** Distant Horizons' block materials (DH_BLOCK_...), by their numbers. */
	public static final String[] FAR_MATERIALS = {
		"UNKNOWN", "LEAVES", "STONE", "WOOD", "METAL", "DIRT", "LAVA", "DEEPSLATE", "SNOW", "SAND", "TERRACOTTA", "NETHER_STONE", "WATER", "GRASS",
		"AIR", "ILLUMINATED"
	};

	/**
	 * Distant Horizons' macros, while Afterburner's far terrain is on: packs with Distant Horizons' programs draw it with their
	 * dh_terrain (as with Iris and Distant Horizons), and take their paths for it in the others (its depth, its fog).
	 */
	public static void addFarTerrain(Map<String, String> m) {
		m.put("DISTANT_HORIZONS", "");
		for (int i = 0; i < FAR_MATERIALS.length; i++) m.put("DH_BLOCK_" + FAR_MATERIALS[i], Integer.toString(i));
	}

	/** Iris's biome categories (CAT_...), by their numbers: what the biome_category uniform holds. */
	public static final String[] BIOME_CATEGORIES = {
		"NONE", "TAIGA", "EXTREME_HILLS", "JUNGLE", "MESA", "PLAINS", "SAVANNA", "ICY", "THE_END", "BEACH", "FOREST", "OCEAN", "DESERT",
		"RIVER", "SWAMP", "MUSHROOM", "NETHER", "MOUNTAIN", "UNDERGROUND"
	};

	/**
	 * Iris's biome macros, which custom uniforms read too: BIOME_PLAINS and so on, the numbers the biome uniform holds
	 * ({@code biomes} in order, vanilla's), CAT_... for biome_category and PPT_NONE, PPT_RAIN, PPT_SNOW for biome_precipitation.
	 */
	public static Map<String, String> addBiomes(Map<String, String> m, List<String> biomes) {
		for (int i = 0; i < biomes.size(); i++) m.put("BIOME_" + biomes.get(i).toUpperCase(Locale.ROOT), Integer.toString(i));
		for (int i = 0; i < BIOME_CATEGORIES.length; i++) m.put("CAT_" + BIOME_CATEGORIES[i], Integer.toString(i));
		m.put("PPT_NONE", "0");
		m.put("PPT_RAIN", "1");
		m.put("PPT_SNOW", "2");
		return m;
	}

	public static void apply(GlslPreprocessor preprocessor, Map<String, String> macros) {
		macros.forEach(preprocessor::define);
	}
}
