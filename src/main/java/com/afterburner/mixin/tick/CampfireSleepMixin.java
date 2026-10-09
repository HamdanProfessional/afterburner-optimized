package com.afterburner.mixin.tick;

import com.afterburner.tick.SleepingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A campfire sleeps ({@link SleepingBlockEntity}) when its tick would do nothing: lit with nothing on it, or out with nothing
 * left to cool down. Food put on wakes it, and so does lighting or putting it out (its block changes).
 */
@Mixin(CampfireBlockEntity.class)
public abstract class CampfireSleepMixin implements SleepingBlockEntity {
	@Shadow
	@Final
	private NonNullList<ItemStack> items;
	@Shadow
	@Final
	private int[] cookingProgress;

	@Inject(method = "cookTick", at = @At("TAIL"))
	private static void afterburner$sleepLit(Level level, BlockPos pos, BlockState state, CampfireBlockEntity entity, CallbackInfo ci) {
		afterburner$sleep(entity);
	}

	@Inject(method = "cooldownTick", at = @At("TAIL"))
	private static void afterburner$sleepOut(Level level, BlockPos pos, BlockState state, CampfireBlockEntity entity, CallbackInfo ci) {
		if (!level.isClientSide()) afterburner$sleep(entity);
	}

	private static void afterburner$sleep(CampfireBlockEntity entity) {
		SleepingBlockEntity sleeper = (SleepingBlockEntity) entity;
		if (sleeper.afterburner$stillIdle() && SleepingBlockEntity.sleeps(entity)) sleeper.afterburner$setAsleep(true);
	}

	@Override
	public boolean afterburner$stillIdle() {
		// Lit, the tick cooks what's on it; out, it cools down what was cooking.
		if (((BlockEntity) (Object) this).getBlockState().getValue(CampfireBlock.LIT)) {
			for (ItemStack stack : this.items) {
				if (!stack.isEmpty()) return false;
			}
		} else {
			for (int progress : this.cookingProgress) {
				if (progress > 0) return false;
			}
		}
		return true;
	}

	@Inject(method = "placeFood", at = @At("HEAD"))
	private void afterburner$wake(CallbackInfoReturnable<Boolean> cir) {
		this.afterburner$setAsleep(false);
	}
}
