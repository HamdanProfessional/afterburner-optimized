package com.afterburner.labs.shaderpack;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The alpha test OptiFine and Iris do for a world program, which old programs count on to cut out leaves and grass: a fragment
 * whose first output's alpha fails {@code function} (OpenGL's: NEVER, LESS, EQUAL, LEQUAL, GREATER, NOTEQUAL, GEQUAL, ALWAYS)
 * against {@code reference} is discarded, and the pack's alphaTestRef is the reference.
 */
public record AlphaTest(String function, float reference) {
	/** None: every fragment kept, alphaTestRef 0.0 (Iris's for what isn't cut out). */
	public static final AlphaTest OFF = new AlphaTest("ALWAYS", 0.0F);
	/** OptiFine's: over a tenth. */
	public static final AlphaTest ONE_TENTH = new AlphaTest("GREATER", 0.1F);
	/** Iris's for water and other see-through terrain: anything not quite invisible. */
	public static final AlphaTest NON_ZERO = new AlphaTest("GREATER", 0.0001F);
	private static final Map<String, String> OPERATORS = Map.of("LESS", "<", "EQUAL", "==", "LEQUAL", "<=", "GREATER", ">", "NOTEQUAL", "!=",
		"GEQUAL", ">=");

	/** A GLSL condition true for the fragments kept, whose alpha is {@code alpha}; null when all are. */
	public @Nullable String keeps(String alpha) {
		if (this.function.equals("ALWAYS")) return null;
		if (this.function.equals("NEVER")) return "false";
		return alpha + " " + OPERATORS.get(this.function) + " " + this.glslReference();
	}

	/** The reference as a GLSL float ("0.5", "0.0001"). */
	public String glslReference() {
		return new BigDecimal(Float.toString(this.reference)).toPlainString();
	}

	/** A test as a directive gives it ("GREATER 0.1", "GL_GEQUAL 0.5", "off"); null if it isn't one. */
	public static @Nullable AlphaTest parse(String value) {
		String[] parts = value.strip().split("\\s+");
		if (parts.length == 1 && (parts[0].equalsIgnoreCase("off") || parts[0].equalsIgnoreCase("false"))) return OFF;
		if (parts.length != 2) return null;
		String function = parts[0].toUpperCase(Locale.ROOT);
		if (function.startsWith("GL_")) function = function.substring("GL_".length());
		if (!function.equals("NEVER") && !function.equals("ALWAYS") && !OPERATORS.containsKey(function)) return null;
		try {
			float reference = Float.parseFloat(parts[1]);
			return Float.isFinite(reference) ? new AlphaTest(function, reference) : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** A pack's alphaTest.&lt;program&gt; directives (shaders.properties): program to its test. */
	static Map<String, AlphaTest> directives(ShaderProperties properties, List<String> warnings) {
		Map<String, AlphaTest> out = new HashMap<>();
		for (ShaderProperties.Entry e : properties.withPrefix("alphaTest.")) {
			String program = e.key().substring("alphaTest.".length());
			AlphaTest test = parse(e.value());
			if (program.isEmpty() || test == null) {
				warnings.add(e.origin() + ": can't read " + e.key() + "=" + e.value());
				continue;
			}
			out.put(program, test);
		}
		return out;
	}
}
