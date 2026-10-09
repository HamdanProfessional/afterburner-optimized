package com.afterburner.mixin.tick;

import com.afterburner.tick.ChunkListener;
import com.afterburner.tick.ParkedTicks;
import com.afterburner.tick.TickWaker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A chunk can run scheduled ticks once its mobs are loaded, it's in simulation distance, and it's ticking (see
 * {@code ServerLevel.isPositionTickingWithEntitiesLoaded}). The world's tick lists are told when each of those may have
 * become true: here when mobs are loaded and when a chunk starts ticking, in {@link SimulationTrackerMixin} for the
 * distance, and in {@link ChunkMapTicksMixin} when an unloading chunk is taken back.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelTicksMixin implements TickWaker {
	@Shadow
	@Final
	private LevelTicks<Block> blockTicks;
	@Shadow
	@Final
	private LevelTicks<Fluid> fluidTicks;
	@Shadow
	@Final
	private PersistentEntitySectionManager<Entity> entityManager;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void afterburner$attachTicks(CallbackInfo ci) {
		((ParkedTicks) blockTicks).afterburner$attach();
		((ParkedTicks) fluidTicks).afterburner$attach();
		((ChunkListener) entityManager).afterburner$listen(this::afterburner$wakeTicks);
	}

	/** Its ticking future is done right after this returns, before the next tick's walk. */
	@Inject(method = "startTickingChunk", at = @At("HEAD"))
	private void afterburner$startsTicking(LevelChunk chunk, CallbackInfo ci) {
		afterburner$wakeTicks(chunk.getPos().pack());
	}

	@Override
	public void afterburner$wakeTicks(long chunk) {
		((ParkedTicks) blockTicks).afterburner$wake(chunk);
		((ParkedTicks) fluidTicks).afterburner$wake(chunk);
	}
}
