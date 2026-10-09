package com.afterburner.labs.shaderpack;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A pack's blend directives (shaders.properties): {@code blend.<program>} for every buffer the program draws, and
 * {@code blend.<program>.<buffer>} for one (colortexN, or the old names gcolor, gdepth, gnormal, composite, gaux1-4), each "off"
 * or four factors (source and destination color, source and destination alpha). Without one, a world program blends as what
 * it draws does in the game, the shadow programs don't, gbuffers_spidereyes is added (SRC_ALPHA ONE ZERO ONE) and the
 * fullscreen ones don't, as with OptiFine and Iris.
 * <p>
 * With OpenGL a pass blends with one function: the buffers' own only turn it on or off, the first buffer blending gives it.
 */
public final class PackBlending {
	/** The old names of colortex0-7. */
	private static final List<String> OLD_NAMES = List.of("gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4");
	/** The factors OptiFine knows, named as the game's. */
	private static final Set<String> FACTORS = Set.of("ZERO", "ONE", "SRC_COLOR", "ONE_MINUS_SRC_COLOR", "DST_COLOR", "ONE_MINUS_DST_COLOR",
		"SRC_ALPHA", "ONE_MINUS_SRC_ALPHA", "DST_ALPHA", "ONE_MINUS_DST_ALPHA", "SRC_ALPHA_SATURATE");
	private static final Optional<Factors> SPIDER_EYES = Optional.of(new Factors("SRC_ALPHA", "ONE", "ZERO", "ONE"));

	/** A blend function's factors, by their names (those of the game's BlendFactor). */
	public record Factors(String srcColor, String dstColor, String srcAlpha, String dstAlpha) {
	}

	/** Program to its blend for all its buffers (empty: off). */
	private final Map<String, Optional<Factors>> programs = new HashMap<>();
	/** Program to buffer to its blend there. */
	private final Map<String, Map<Integer, Optional<Factors>>> buffers = new HashMap<>();

	private PackBlending() {
	}

	static PackBlending parse(ShaderProperties properties, List<String> warnings) {
		PackBlending out = new PackBlending();
		for (ShaderProperties.Entry e : properties.withPrefix("blend.")) {
			String rest = e.key().substring("blend.".length());
			int dot = rest.indexOf('.');
			String program = dot < 0 ? rest : rest.substring(0, dot);
			Optional<Factors> blend = function(e.value());
			if (program.isEmpty() || blend == null) {
				warnings.add(e.origin() + ": can't read " + e.key() + "=" + e.value());
				continue;
			}
			if (dot < 0) {
				out.programs.put(program, blend);
				continue;
			}
			int buffer = buffer(rest.substring(dot + 1));
			if (buffer < 0) {
				warnings.add(e.origin() + ": " + e.key() + " names no buffer we know");
				continue;
			}
			out.buffers.computeIfAbsent(program, p -> new HashMap<>()).put(buffer, blend);
		}
		return out;
	}

	/** "off", or four factors; null if it's neither. */
	private static @Nullable Optional<Factors> function(String value) {
		String v = value.strip();
		if (v.equalsIgnoreCase("off")) return Optional.empty();
		String[] parts = v.toUpperCase(Locale.ROOT).split("\\s+");
		if (parts.length != 4) return null;
		for (String p : parts) if (!FACTORS.contains(p)) return null;
		return Optional.of(new Factors(parts[0], parts[1], parts[2], parts[3]));
	}

	/** The buffer a name stands for, or -1. */
	private static int buffer(String name) {
		int old = OLD_NAMES.indexOf(name);
		if (old >= 0) return old;
		if (!name.startsWith("colortex")) return -1;
		try {
			int b = Integer.parseInt(name.substring("colortex".length()));
			return b >= 0 && b < 16 ? b : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/**
	 * How program {@code program} (its file's name: "gbuffers_terrain", "composite5", "shadow"), drawing for {@code slot} (the
	 * program it stands in for: "gbuffers_water", "shadow_solid"), blends into colortex {@code buffer} (-1 for none of them:
	 * the screen, the shadowcolor buffers): empty for off, null for as the game blends what it draws (off for fullscreen
	 * passes).
	 */
	public @Nullable Optional<Factors> blend(String slot, String program, int buffer) {
		Map<Integer, Optional<Factors>> own = this.buffers.get(program);
		if (own != null && buffer >= 0) {
			Optional<Factors> b = own.get(buffer);
			if (b != null) return b;
		}
		Optional<Factors> all = this.programs.get(program);
		if (all != null) return all;
		if (slot.equals("shadow") || slot.startsWith("shadow_")) return Optional.empty();
		if (slot.equals("gbuffers_spidereyes")) return SPIDER_EYES;
		return null;
	}

	/** How many directives were read: for checking packs. */
	public int count() {
		int n = this.programs.size();
		for (Map<Integer, Optional<Factors>> m : this.buffers.values()) n += m.size();
		return n;
	}
}
