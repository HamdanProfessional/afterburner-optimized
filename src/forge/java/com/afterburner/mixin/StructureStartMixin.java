package com.afterburner.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A structure spans many chunks and each chunk places its own slice. Some structure pieces remember things while
 * placing (like the ground height, or whether the witch was spawned), so with parallel worldgen two chunks of the
 * same structure take turns.
 */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin {
	@WrapMethod(method = "placeInChunk")
	private void afterburner$oneChunkAtATime(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
			RandomSource random, BoundingBox chunkBB, ChunkPos chunkPos, Operation<Void> original) {
		synchronized (this) {
			original.call(level, structureManager, generator, random, chunkBB, chunkPos);
		}
	}
}
