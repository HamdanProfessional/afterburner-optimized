package com.afterburner.labs.shaderpack;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * The GLSL the game hands the driver (its SPIR-V turned back into version 330) needs extensions SPIRV-Cross doesn't add: storage
 * buffers' and their memory qualifiers' (readonly, ...: the image extension's words), and those of the built-ins newer than 330
 * that packs of version 400 and up use (bitfieldInsert, textureGather, packHalf2x16, ...).
 */
public final class DriverGlsl {
	private static final Pattern STORAGE_BLOCK = Pattern.compile("\\bbuffer\\s+\\w+\\s*\\{");
	private static final Pattern MEMORY_QUALIFIER = Pattern.compile("\\b(?:readonly|writeonly|coherent|volatile|restrict)\\b");
	/** Extensions and the built-ins of theirs that version 330 lacks (SPIRV-Cross renames the pack's own functions of those names). */
	private static final Map<String, Pattern> FUNCTIONS = Map.of(
		"GL_ARB_gpu_shader5", calls("bitfieldExtract|bitfieldInsert|bitfieldReverse|bitCount|findLSB|findMSB|uaddCarry|usubBorrow|umulExtended"
			+ "|imulExtended|frexp|ldexp|packUnorm4x8|packSnorm4x8|unpackUnorm4x8|unpackSnorm4x8|fma|interpolateAtCentroid|interpolateAtSample"
			+ "|interpolateAtOffset|textureGather|textureGatherOffset|textureGatherOffsets"),
		"GL_ARB_shading_language_packing", calls("packUnorm2x16|packSnorm2x16|unpackUnorm2x16|unpackSnorm2x16|packHalf2x16|unpackHalf2x16"),
		"GL_ARB_texture_query_lod", calls("textureQueryLod"),
		"GL_ARB_texture_query_levels", calls("textureQueryLevels"),
		"GL_ARB_derivative_control", calls("dFdxFine|dFdyFine|dFdxCoarse|dFdyCoarse|fwidthFine|fwidthCoarse"),
		"GL_ARB_shader_image_size", calls("imageSize"));

	private DriverGlsl() {
	}

	private static Pattern calls(String names) {
		return Pattern.compile("\\b(?:" + names + ")\\s*\\(");
	}

	/** The source with the extensions it needs added after its #version line. */
	public static String source(String source) {
		int line = source.indexOf('\n', source.indexOf("#version"));
		if (line < 0 || !source.startsWith("#version 3", source.indexOf("#version"))) return source;
		StringBuilder add = new StringBuilder();
		if (STORAGE_BLOCK.matcher(source).find()) {
			require(source, "GL_ARB_shader_storage_buffer_object", add);
			if (MEMORY_QUALIFIER.matcher(source).find()) require(source, "GL_ARB_shader_image_load_store", add);
		}
		for (Map.Entry<String, Pattern> e : FUNCTIONS.entrySet()) {
			if (e.getValue().matcher(source).find()) require(source, e.getKey(), add);
		}
		if (add.isEmpty()) return source;
		return source.substring(0, line + 1) + add + source.substring(line + 1);
	}

	private static void require(String source, String extension, StringBuilder add) {
		if (!source.contains(extension) && add.indexOf(extension) < 0) add.append("#extension ").append(extension).append(" : require\n");
	}
}
