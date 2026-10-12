package com.afterburner.mixin;

import com.afterburner.chunk.ParallelWorldgen;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkStep.class)
public abstract class ChunkStepMixin {
	@WrapMethod(method = "apply")
	private CompletableFuture<ChunkAccess> afterburner$runInParallel(WorldGenContext context,
			StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
		return ParallelWorldgen.apply((ChunkStep) (Object) this, context.level(), chunk, () -> original.call(context, cache, chunk));
	}
}
