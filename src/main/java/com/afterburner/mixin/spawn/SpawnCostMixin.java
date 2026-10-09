package com.afterburner.mixin.spawn;

import com.afterburner.spawn.SpawnCosts;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;

/** Each spawn try's cost at its spot, and each mob's after it spawned, without a lookup where no spot has one: see {@link SpawnCosts}. */
@Mixin(NaturalSpawner.class)
public abstract class SpawnCostMixin {
	@WrapMethod(method = "getRoughBiome")
	private static Biome afterburner$roughBiome(BlockPos pos, ChunkAccess chunk, Operation<Biome> original) {
		SpawnCosts costs = SpawnCosts.of(chunk);
		return costs != null ? costs.roughBiome(pos, chunk, original) : original.call(pos, chunk);
	}
}
