package com.afterburner.labs.shaderpack.game;

import com.afterburner.labs.shaderpack.StandardMacros;
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
 * The biome uniforms packs read as with Iris: biome (vanilla's biomes numbered in the order the game declares them, any other
 * 0; BIOME_PLAINS and so on name the numbers) and biome_category (the first of a list of the biome's tags it has, CAT_...).
 */
final class PackBiomes {
	/** Tags to categories ({@link StandardMacros#BIOME_CATEGORIES}): the first the biome has is its category, PLAINS if none. */
	private static final List<Map.Entry<TagKey<Biome>, Integer>> CATEGORIES = List.of(
		category(BiomeTags.WITHOUT_WANDERING_TRADER_SPAWNS, "NONE"),
		category(BiomeTags.HAS_VILLAGE_SNOWY, "ICY"),
		category(BiomeTags.IS_HILL, "EXTREME_HILLS"),
		category(BiomeTags.IS_TAIGA, "TAIGA"),
		category(BiomeTags.IS_OCEAN, "OCEAN"),
		category(BiomeTags.IS_JUNGLE, "JUNGLE"),
		category(BiomeTags.IS_FOREST, "FOREST"),
		category(BiomeTags.IS_BADLANDS, "MESA"),
		category(BiomeTags.IS_NETHER, "NETHER"),
		category(BiomeTags.IS_END, "THE_END"),
		category(BiomeTags.IS_BEACH, "BEACH"),
		category(BiomeTags.HAS_DESERT_PYRAMID, "DESERT"),
		category(BiomeTags.IS_RIVER, "RIVER"),
		category(BiomeTags.ALLOWS_SURFACE_SLIME_SPAWNS, "SWAMP"),
		category(BiomeTags.WITHOUT_ZOMBIE_SIEGES, "MUSHROOM"),
		category(BiomeTags.IS_MOUNTAIN, "MOUNTAIN"));
	private static final int PLAINS = List.of(StandardMacros.BIOME_CATEGORIES).indexOf("PLAINS");

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
		for (Map.Entry<TagKey<Biome>, Integer> c : CATEGORIES) {
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

	private static Map.Entry<TagKey<Biome>, Integer> category(TagKey<Biome> tag, String name) {
		return Map.entry(tag, List.of(StandardMacros.BIOME_CATEGORIES).indexOf(name));
	}
}
