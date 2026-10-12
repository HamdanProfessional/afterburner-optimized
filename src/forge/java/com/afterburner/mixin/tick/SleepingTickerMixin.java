package com.afterburner.mixin.tick;

import com.afterburner.tick.SleepingBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips the ticks of a sleeping block entity ({@link SleepingBlockEntity}). */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public class SleepingTickerMixin {
	@Shadow
	@Final
	private BlockEntity blockEntity;

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void afterburner$skipAsleep(CallbackInfo ci) {
		SleepingBlockEntity sleeper = (SleepingBlockEntity) this.blockEntity;
		if (!sleeper.afterburner$asleep()) return;
		if (SleepingBlockEntity.CHECK && !this.blockEntity.isRemoved() && !sleeper.afterburner$stillIdle()) {
			SleepingBlockEntity.wrong(this.blockEntity);
			sleeper.afterburner$setAsleep(false);
			return;
		}
		ci.cancel();
	}
}
