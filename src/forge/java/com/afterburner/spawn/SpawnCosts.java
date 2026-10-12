package com.afterburner.spawn;

import com.afterburner.mixin.spawn.AttributeSamplerAccessor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeLayer;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every tick vanilla asks each mob's spot for its spawn cost (how much it holds off more mobs spawning near it), and each
 * spawn try asks the spot it tries: a biome lookup each time. Only a few nether biomes have costs. In a world whose
 * generator makes none of those, where no chunk read from disk or given biomes since (/fillbiome) holds one, and where
 * nothing but the dimension and the biomes sets which mobs spawn, every answer is "none", so it isn't looked up.
 */
public final class SpawnCosts {
	/** The lookup, as spawning calls it. */
	public static final String GET_VALUE = "Lnet/minecraft/world/attribute/EnvironmentAttributeSystem;getValue(Lnet/minecraft/world/attribute/EnvironmentAttribute;"
			+ "Lnet/minecraft/core/BlockPos;)Ljava/lang/Object;";

	/** The world's attributes, with this kept on them. */
	public interface Known {
		Map<EnvironmentAttribute<?>, ?> afterburner$samplers();

		@Nullable SpawnCosts afterburner$spawnCosts();

		void afterburner$spawnCosts(SpawnCosts costs);
	}

	/** The biomes that give a spot a cost in this world. */
	private final Set<Biome> costly = new ReferenceOpenHashSet<>();
	/** Whether a spot may have a cost: then everything is looked up as vanilla does. */
	private volatile boolean some;

	private SpawnCosts(Level level, Known system) {
		some = !workOut(level, system);
		if (!some && level instanceof ServerLevel server) {
			for (Holder<Biome> biome : server.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
				if (costly.contains(biome.value())) some = true;
			}
		} else {
			some = true;
		}
	}

	/** {@code system.getValue(attribute, pos)} as vanilla's spawning asks it, without the lookup where the answer is known. */
	public static Object value(Level level, EnvironmentAttributeSystem system, EnvironmentAttribute<?> attribute, BlockPos pos, Operation<Object> original) {
		if (attribute != EnvironmentAttributes.NATURAL_MOB_SPAWNS || of(level, system).some) return original.call(system, attribute, pos);
		if (SpawnLookups.CHECK) SpawnLookups.checkCost(((MobSpawnSettings) original.call(system, attribute, pos)).allSpawnCosts().isEmpty());
		return MobSpawnSettings.EMPTY;
	}

	/** A chunk of that world was read from disk or given biomes: if it holds a biome with costs, they're looked up from now on. */
	public static void arrived(ServerLevel level, ChunkAccess chunk) {
		SpawnCosts costs = of(level, level.environmentAttributes());
		if (costs.some) return;
		for (LevelChunkSection section : chunk.getSections()) {
			if (section.getBiomes().maybeHas(biome -> costs.costly.contains(biome.value()))) {
				costs.some = true;
				return;
			}
		}
	}

	private static SpawnCosts of(Level level, EnvironmentAttributeSystem system) {
		Known known = (Known) system;
		SpawnCosts costs = known.afterburner$spawnCosts();
		if (costs != null) return costs;
		synchronized (known) {
			costs = known.afterburner$spawnCosts();
			if (costs == null) known.afterburner$spawnCosts(costs = new SpawnCosts(level, known));
			return costs;
		}
	}

	/** Fills {@link #costly}; false if it can't be told which spots have costs. */
	private boolean workOut(Level level, Known system) {
		EnvironmentAttribute<MobSpawnSettings> attribute = EnvironmentAttributes.NATURAL_MOB_SPAWNS;
		if (!MobSpawnSettings.EMPTY.allSpawnCosts().isEmpty()) return false;
		Object sampler = system.afterburner$samplers().get(attribute);
		if (sampler == null) return attribute.defaultValue().allSpawnCosts().isEmpty();
		AttributeSamplerAccessor values = (AttributeSamplerAccessor) sampler;
		MobSpawnSettings base = (MobSpawnSettings) values.afterburner$baseValue();
		List<?> layers = values.afterburner$layers();
		List<Biome> biomes = level.registryAccess().lookupOrThrow(Registries.BIOME).listElements().map(Holder::value).toList();
		boolean fromBiomes = biomes.stream().anyMatch(biome -> biome.getAttributes().contains(attribute));
		// After the dimension's own value only the biomes' layer is left: none for the time of day or the weather.
		if (layers.size() != (fromBiomes ? 1 : 0) || fromBiomes && !(layers.getFirst() instanceof EnvironmentAttributeLayer.Positional<?>)) return false;
		if (!attribute.sanitizeValue(base).allSpawnCosts().isEmpty()) return false;
		for (Biome biome : biomes) {
			if (!attribute.sanitizeValue(biome.getAttributes().applyModifier(attribute, base)).allSpawnCosts().isEmpty()) costly.add(biome);
		}
		return true;
	}
}
