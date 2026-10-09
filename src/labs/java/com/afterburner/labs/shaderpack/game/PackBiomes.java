package com.afterburner.labs.shaderpack.game;

import com.afterburner.labs.shaderpack.PackMacros;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.jspecify.annotations.Nullable;

/**
 * The biome uniforms packs read: biome (vanilla's biomes numbered in the order the game declares them, any other 0;
 * BIOME_PLAINS and so on name the numbers) and biome_category (CAT_..., the categories biomes had in the game before 1.19).
 */
final class PackBiomes {
	/** Each vanilla biome's category ({@link PackMacros#BIOME_CATEGORIES}), as the game had them, with the newer biomes placed alike. */
	private static final Map<String, Integer> VANILLA = vanilla(
		"OCEAN", "ocean deep_ocean warm_ocean lukewarm_ocean deep_lukewarm_ocean cold_ocean deep_cold_ocean frozen_ocean deep_frozen_ocean",
		"PLAINS", "plains sunflower_plains meadow cherry_grove",
		"DESERT", "desert",
		"SAVANNA", "savanna savanna_plateau windswept_savanna",
		"FOREST", "forest flower_forest birch_forest old_growth_birch_forest dark_forest pale_garden",
		"TAIGA", "taiga old_growth_pine_taiga old_growth_spruce_taiga snowy_taiga",
		"EXTREME_HILLS", "windswept_hills windswept_gravelly_hills windswept_forest",
		"MOUNTAIN", "grove snowy_slopes frozen_peaks jagged_peaks stony_peaks",
		"JUNGLE", "jungle sparse_jungle bamboo_jungle",
		"MESA", "badlands eroded_badlands wooded_badlands",
		"ICY", "snowy_plains ice_spikes",
		"SWAMP", "swamp mangrove_swamp",
		"RIVER", "river frozen_river",
		"BEACH", "beach snowy_beach stony_shore",
		"MUSHROOM", "mushroom_fields",
		"UNDERGROUND", "dripstone_caves lush_caves deep_dark",
		"NETHER", "nether_wastes soul_sand_valley crimson_forest warped_forest basalt_deltas",
		"THE_END", "the_end small_end_islands end_midlands end_highlands end_barrens",
		"NONE", "the_void");
	/** For biomes of other mods: the first of these tags the biome has, PLAINS if none. */
	private static final List<Map.Entry<TagKey<Biome>, Integer>> FALLBACK = List.of(
		category(BiomeTags.IS_NETHER, "NETHER"),
		category(BiomeTags.IS_END, "THE_END"),
		category(BiomeTags.IS_OCEAN, "OCEAN"),
		category(BiomeTags.IS_RIVER, "RIVER"),
		category(BiomeTags.IS_BEACH, "BEACH"),
		category(BiomeTags.IS_BADLANDS, "MESA"),
		category(BiomeTags.IS_JUNGLE, "JUNGLE"),
		category(BiomeTags.IS_SAVANNA, "SAVANNA"),
		category(BiomeTags.IS_TAIGA, "TAIGA"),
		category(BiomeTags.IS_FOREST, "FOREST"),
		category(BiomeTags.IS_MOUNTAIN, "MOUNTAIN"),
		category(BiomeTags.IS_HILL, "EXTREME_HILLS"));
	private static final int PLAINS = List.of(PackMacros.BIOME_CATEGORIES).indexOf("PLAINS");

	private static @Nullable Map<String, Integer> ids;

	private PackBiomes() {
	}

	/** Vanilla's biomes (their names, without "minecraft:"), in the order the game declares them: by their numbers. */
	static List<String> names() {
		return new ArrayList<>(ids().keySet());
	}

	static int id(Holder<Biome> biome) {
		return biome.unwrapKey().filter(key -> key.identifier().getNamespace().equals("minecraft"))
			.map(key -> ids().getOrDefault(key.identifier().getPath(), 0)).orElse(0);
	}

	static int category(Holder<Biome> biome) {
		Integer known = biome.unwrapKey().filter(key -> key.identifier().getNamespace().equals("minecraft"))
			.map(key -> VANILLA.get(key.identifier().getPath())).orElse(null);
		if (known != null) return known;
		for (Map.Entry<TagKey<Biome>, Integer> c : FALLBACK) {
			if (biome.is(c.getKey())) return c.getValue();
		}
		return PLAINS;
	}

	private static synchronized Map<String, Integer> ids() {
		if (ids == null) {
			// The fields are declared as the biomes are registered, one by one.
			Map<String, Integer> m = new LinkedHashMap<>();
			for (Field f : Biomes.class.getDeclaredFields()) {
				if (!Modifier.isStatic(f.getModifiers()) || f.getType() != ResourceKey.class) continue;
				try {
					m.putIfAbsent(((ResourceKey<?>) f.get(null)).identifier().getPath(), m.size());
				} catch (IllegalAccessException | ClassCastException e) {
					// Not a biome's key.
				}
			}
			ids = m;
		}
		return ids;
	}

	/** Pairs of a category and the biomes in it (names without "minecraft:", separated by spaces). */
	private static Map<String, Integer> vanilla(String... pairs) {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			int category = List.of(PackMacros.BIOME_CATEGORIES).indexOf(pairs[i]);
			for (String name : pairs[i + 1].split(" ")) m.put(name, category);
		}
		return m;
	}

	private static Map.Entry<TagKey<Biome>, Integer> category(TagKey<Biome> tag, String name) {
		return Map.entry(tag, List.of(PackMacros.BIOME_CATEGORIES).indexOf(name));
	}
}
