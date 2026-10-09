package com.afterburner.spawn;

import com.afterburner.mixin.spawn.SpawnCostAccessor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Every tick each mob's spot is asked for its spawn cost, and each spawn try asks the spot it tries: in 1.21.1 that is a
 * quart-position biome lookup ({@code NaturalSpawner.getRoughBiome}) and then the biome's spawn cost map. Only a few nether
 * biomes have costs. In a world whose generator makes none of those, where no chunk read from disk or given biomes since
 * (/fillbiome) holds one, every answer is "none", so the lookup isn't made.
 */
public final class SpawnCosts {
	/** The costs of each world, kept while it is loaded. */
	private static final Map<ServerLevel, SpawnCosts> LEVELS = new WeakHashMap<>();

	/** A biome whose spawn settings have no costs, to answer with where none may have any. */
	private final Biome noCosts;
	/** Whether a spot may have a cost: then everything is looked up as vanilla does. */
	private volatile boolean some;

	private SpawnCosts(ServerLevel level) {
		Biome sample = null;
		boolean some = false;
		for (Holder<Biome> holder : level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
			Biome biome = holder.value();
			if (costly(biome)) some = true;
			else if (sample == null) sample = biome;
		}
		this.noCosts = sample;
		this.some = some || sample == null;
	}

	/** This world's costs. */
	public static SpawnCosts of(ServerLevel level) {
		synchronized (LEVELS) {
			return LEVELS.computeIfAbsent(level, SpawnCosts::new);
		}
	}

	/** This chunk's world's costs, or null if the chunk isn't a loaded world's. */
	public static @Nullable SpawnCosts of(ChunkAccess chunk) {
		return chunk.levelHeightAccessor instanceof ServerLevel level ? of(level) : null;
	}

	/** {@code NaturalSpawner.getRoughBiome} without the lookup where the answer can't matter. */
	public Biome roughBiome(BlockPos pos, ChunkAccess chunk, Operation<Biome> original) {
		if (some) return original.call(pos, chunk);
		if (SpawnLookups.CHECK) SpawnLookups.checkCost(!costly(original.call(pos, chunk)));
		return noCosts;
	}

	/** A chunk of that world was read from disk or given biomes: if it holds a biome with costs, they're looked up from now on. */
	public static void arrived(ServerLevel level, ChunkAccess chunk) {
		SpawnCosts costs = of(level);
		if (costs.some) return;
		for (LevelChunkSection section : chunk.getSections()) {
			if (section.getBiomes().maybeHas(biome -> costly(biome.value()))) {
				costs.some = true;
				return;
			}
		}
	}

	/** Whether that biome's spawn settings give anything a cost. */
	private static boolean costly(Biome biome) {
		return !((SpawnCostAccessor) (Object) biome.getMobSettings()).afterburner$costs().isEmpty();
	}
}
