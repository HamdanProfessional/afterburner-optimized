package com.afterburner.mixin.tick;

import com.afterburner.tick.SleepingBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Whether a block entity sleeps ({@link SleepingBlockEntity}), and what wakes any of them: being marked changed, its block changing, loading, components. */
@Mixin(BlockEntity.class)
public class BlockEntitySleepMixin implements SleepingBlockEntity {
	@Unique
	private boolean afterburner$asleep;

	@Override
	public boolean afterburner$asleep() {
		return this.afterburner$asleep;
	}

	@Override
	public void afterburner$setAsleep(boolean asleep) {
		this.afterburner$asleep = asleep;
	}

	@Override
	public boolean afterburner$stillIdle() {
		return true;
	}

	@Inject(method = {"setChanged()V", "setBlockState", "loadWithComponents", "loadCustomOnly", "applyComponents"}, at = @At("HEAD"))
	private void afterburner$wake(CallbackInfo ci) {
		this.afterburner$asleep = false;
	}
}
