package com.afterburner.labs.shaderpack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Which pack files make up each program for one dimension, following OptiFine's fallbacks (gbuffers_water falls back to
 * gbuffers_terrain, then gbuffers_textured_lit, ...). A program comes from the dimension's folder (world0, world-1, ...) when
 * it's there, otherwise from the pack's root, and can be turned off with {@code program.[world0/]<name>.enabled}.
 * <p>
 * Compute programs are found as Iris has them: {@code <pass>.csh} and {@code <pass>_a.csh} to {@code <pass>_z.csh}, run in that
 * order before the pass's own program (if it has one).
 */
public final class PackPrograms {
	public record Source(String name, String vertex, String fragment) {}

	/** Program to the one used when it's missing; null ends the chain (nothing drawn with a pack program: vanilla stays). */
	private static final Map<String, @Nullable String> FALLBACKS = new LinkedHashMap<>();

	static {
		fallback("gbuffers_basic", null);
		fallback("gbuffers_line", "gbuffers_basic");
		fallback("gbuffers_textured", "gbuffers_basic");
		fallback("gbuffers_textured_lit", "gbuffers_textured");
		fallback("gbuffers_skybasic", "gbuffers_basic");
		fallback("gbuffers_skytextured", "gbuffers_textured");
		fallback("gbuffers_clouds", "gbuffers_textured");
		fallback("gbuffers_terrain", "gbuffers_textured_lit");
		fallback("gbuffers_terrain_solid", "gbuffers_terrain");
		fallback("gbuffers_terrain_cutout", "gbuffers_terrain");
		fallback("gbuffers_damagedblock", "gbuffers_terrain");
		fallback("gbuffers_block", "gbuffers_terrain");
		fallback("gbuffers_block_translucent", "gbuffers_block");
		fallback("gbuffers_beaconbeam", "gbuffers_textured");
		fallback("gbuffers_item", "gbuffers_textured_lit");
		fallback("gbuffers_entities", "gbuffers_textured_lit");
		fallback("gbuffers_entities_translucent", "gbuffers_entities");
		fallback("gbuffers_entities_glowing", "gbuffers_entities");
		fallback("gbuffers_lightning", "gbuffers_entities");
		fallback("gbuffers_armor_glint", "gbuffers_textured");
		fallback("gbuffers_spidereyes", "gbuffers_textured");
		fallback("gbuffers_hand", "gbuffers_textured_lit");
		fallback("gbuffers_weather", "gbuffers_textured_lit");
		fallback("gbuffers_particles", "gbuffers_textured_lit");
		fallback("gbuffers_particles_translucent", "gbuffers_particles");
		fallback("gbuffers_water", "gbuffers_terrain");
		fallback("gbuffers_hand_water", "gbuffers_hand");
		fallback("shadow", null);
		fallback("shadow_solid", "shadow");
		fallback("shadow_cutout", "shadow");
		fallback("shadow_water", "shadow");
		fallback("shadow_entities", "shadow");
		fallback("shadow_lightning", "shadow_entities");
		fallback("shadow_block", "shadow");
		fallback("final", null);
		// Distant Horizons' terrain: Afterburner's far terrain with a pack (only with its macros, see PackLoader).
		fallback("dh_terrain", null);
		// Its water: without it, drawn with dh_terrain (as Iris does).
		fallback("dh_water", null);
	}

	private static void fallback(String name, @Nullable String to) {
		FALLBACKS.put(name, to);
	}

	/** The fullscreen pass groups, in the order they run. */
	public static final List<String> PASS_GROUPS = List.of("shadowcomp", "prepare", "deferred", "composite");
	/** The groups compute programs can be in, in the order they run: setup once, begin at the start of every frame, then as passes. */
	public static final List<String> COMPUTE_GROUPS = List.of("setup", "begin", "shadowcomp", "prepare", "deferred", "composite");

	private final String folder;
	private final Map<String, Source> found = new LinkedHashMap<>();
	/** Pass name to its compute programs' files, in the order they run. */
	private final Map<String, List<String>> computes = new LinkedHashMap<>();

	private PackPrograms(String folder) {
		this.folder = folder;
	}

	/**
	 * Finds the programs. {@code folder} is the dimension's folder ("world0"), or "" for the root only; {@code enabled} gets
	 * {@code program.*.enabled} keys and says whether a program may be used.
	 */
	public static PackPrograms load(PackFiles pack, String folder, Predicate<String> enabled) throws IOException {
		PackPrograms set = new PackPrograms(folder);
		List<String> names = new ArrayList<>(FALLBACKS.keySet());
		for (String group : PASS_GROUPS) {
			names.add(group);
			for (int i = 1; i < 100; i++) names.add(group + i);
		}
		for (String name : names) {
			Source source = set.find(pack, name);
			if (source == null) continue;
			String worldKey = folder.isEmpty() ? null : "program." + folder + "/" + name + ".enabled";
			if (worldKey != null && !enabled.test(worldKey)) continue;
			if (!enabled.test("program." + name + ".enabled")) continue;
			set.found.put(name, source);
		}

		Set<String> csh = new HashSet<>();
		for (String path : pack.list()) if (path.endsWith(".csh")) csh.add(path);
		if (csh.isEmpty()) return set;
		for (String group : COMPUTE_GROUPS) {
			for (int i = 0; i < 100; i++) {
				String name = i == 0 ? group : group + i;
				List<String> files = set.findComputes(csh, name);
				if (files.isEmpty()) continue;
				String worldKey = folder.isEmpty() ? null : "program." + folder + "/" + name + ".enabled";
				if (worldKey != null && !enabled.test(worldKey)) continue;
				if (!enabled.test("program." + name + ".enabled")) continue;
				set.computes.put(name, files);
			}
		}
		return set;
	}

	/** A pass's compute files: from the dimension's folder if it has any, otherwise from the root. */
	private List<String> findComputes(Set<String> csh, String name) {
		List<String> out = new ArrayList<>();
		for (String dir : this.folder.isEmpty() ? new String[] {"/"} : new String[] {"/" + this.folder + "/", "/"}) {
			if (csh.contains(dir + name + ".csh")) out.add(dir + name + ".csh");
			for (char c = 'a'; c <= 'z'; c++) {
				String path = dir + name + "_" + c + ".csh";
				if (csh.contains(path)) out.add(path);
			}
			if (!out.isEmpty()) return out;
		}
		return out;
	}

	/** Pass name ("shadowcomp", "composite1", ...) to its compute programs' files, in the order the passes run. */
	public Map<String, List<String>> computes() {
		return this.computes;
	}

	private @Nullable Source find(PackFiles pack, String name) throws IOException {
		for (String dir : this.folder.isEmpty() ? new String[] {"/"} : new String[] {"/" + this.folder + "/", "/"}) {
			String vsh = dir + name + ".vsh";
			String fsh = dir + name + ".fsh";
			if (pack.exists(vsh) && pack.exists(fsh)) return new Source(name, vsh, fsh);
		}
		return null;
	}

	/** The program used for {@code name}, after fallbacks, or null if none applies. */
	public @Nullable Source get(String name) {
		String current = name;
		while (current != null) {
			Source source = this.found.get(current);
			if (source != null) return source;
			current = FALLBACKS.get(current);
		}
		return null;
	}

	/** The passes of a group (composite, composite1, ...) that exist, in order. */
	public List<Source> passes(String group) {
		List<Source> out = new ArrayList<>();
		Source first = this.found.get(group);
		if (first != null) out.add(first);
		for (int i = 1; i < 100; i++) {
			Source s = this.found.get(group + i);
			if (s != null) out.add(s);
		}
		return out;
	}

	/** Every program found (not fallbacks), by name. */
	public Map<String, Source> all() {
		return this.found;
	}

	/**
	 * The folder for a dimension: dimension.properties maps folders to dimension ids
	 * ({@code dimension.world0 = minecraft:overworld}); without it the usual world0, world-1 and world1. Returns "" when the
	 * pack has no folder for it.
	 */
	public static String dimensionFolder(PackFiles pack, @Nullable PackProperties dimensions, String dimensionId) throws IOException {
		if (dimensions != null) {
			for (PackProperties.Entry e : dimensions.withPrefix("dimension.")) {
				for (String id : e.value().split("[\\s,]+")) {
					if (id.equals(dimensionId) || (id.equals("*") && !dimensionId.isEmpty())) return e.key().substring("dimension.".length());
				}
			}
		}
		String folder = switch (dimensionId) {
			case "minecraft:overworld" -> "world0";
			case "minecraft:the_nether" -> "world-1";
			case "minecraft:the_end" -> "world1";
			default -> "";
		};
		if (folder.isEmpty()) return "";
		for (String name : FALLBACKS.keySet()) if (pack.exists("/" + folder + "/" + name + ".fsh")) return folder;
		for (String group : PASS_GROUPS) if (pack.exists("/" + folder + "/" + group + ".fsh")) return folder;
		return "";
	}
}
