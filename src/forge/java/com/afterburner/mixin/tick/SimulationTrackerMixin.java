package com.afterburner.mixin.tick;

import com.afterburner.tick.ChunkListener;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.SimulationChunkTracker;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.LongConsumer;

/** Reports each chunk that comes into block ticking range (simulation distance), see {@link ServerLevelTicksMixin}. */
@Mixin(SimulationChunkTracker.class)
public abstract class SimulationTrackerMixin implements ChunkListener {
	@Shadow
	@Final
	protected Long2ByteMap chunks;

	@Unique
	private @Nullable LongConsumer afterburner$listener;

	@Override
	public void afterburner$listen(LongConsumer listener) {
		afterburner$listener = listener;
	}

	@Inject(method = "setLevel", at = @At("HEAD"))
	private void afterburner$levelChanged(long chunk, int level, CallbackInfo ci) {
		if (afterburner$listener != null && ChunkLevel.isBlockTicking(level) && !ChunkLevel.isBlockTicking(chunks.get(chunk))) {
			afterburner$listener.accept(chunk);
		}
	}
}
