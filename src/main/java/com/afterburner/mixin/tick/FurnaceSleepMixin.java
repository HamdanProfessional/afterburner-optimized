package com.afterburner.mixin.tick;

import com.afterburner.tick.SleepingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A furnace sleeps ({@link SleepingBlockEntity}) when its tick would do nothing: no fuel burning, the cooking timer at 0,
 * and either nothing to cook or no fuel to light. Putting an item in wakes it.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class FurnaceSleepMixin implements SleepingBlockEntity {
	@Shadow
	protected NonNullList<ItemStack> items;
	@Shadow
	private int litTime;
	@Shadow
	private int cookingProgress;

	@Inject(method = "serverTick", at = @At("TAIL"))
	private static void afterburner$sleep(Level level, BlockPos pos, BlockState state, AbstractFurnaceBlockEntity entity, CallbackInfo ci) {
		SleepingBlockEntity sleeper = (SleepingBlockEntity) entity;
		if (sleeper.afterburner$stillIdle() && SleepingBlockEntity.sleeps(entity)) sleeper.afterburner$setAsleep(true);
	}

	@Override
	public boolean afterburner$stillIdle() {
		// The tick's way through when it isn't lit, has nothing to light it with and isn't cooling down.
		return this.litTime <= 0 && this.cookingProgress <= 0 && (this.items.get(0).isEmpty() || this.items.get(1).isEmpty());
	}

	@Inject(method = {"setItem", "setItems"}, at = @At("HEAD"))
	private void afterburner$wake(CallbackInfo ci) {
		this.afterburner$setAsleep(false);
	}
}
