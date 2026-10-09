package com.afterburner.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Every living entity looks up the block it stands on every tick to see whether to slow it down for being frozen, and only
 * then asks whether it's frozen at all. Asking first skips the lookup for the ones that aren't (nearly all): vanilla does
 * nothing for them either way.
 */
@Mixin(LivingEntity.class)
public abstract class MobTicksLivingMixin {
	@WrapOperation(method = "tryAddFrost",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getBlockStateOnLegacy()Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState afterburner$frozenFirst(LivingEntity self, Operation<BlockState> original) {
		return self.getTicksFrozen() > 0 ? original.call(self) : Blocks.AIR.defaultBlockState();
	}
}
