package com.afterburner.mixin.redstone;

import com.afterburner.redstone.WireReads;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A wire's new power, reading each block around it once ({@link WireReads}). */
@Mixin(RedStoneWireBlock.class)
public abstract class WireEvaluatorMixin {
	@Unique
	private final WireReads afterburner$reads = new WireReads();
	/** Ours, to compare with vanilla's when it returns (check mode), or -1. */
	@Unique
	private int afterburner$ours = -1;

	@Inject(method = "calculateTargetStrength", at = @At("HEAD"), cancellable = true)
	private void afterburner$readOnce(Level level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
		// Another mod's wire may work its power out differently.
		if (((Object) this).getClass() != RedStoneWireBlock.class || this.afterburner$reads.busy()) {
			return;
		}
		int strength = this.afterburner$reads.targetStrength(level, pos, (RedStoneWireBlock) (Object) this);
		if (WireReads.CHECK) {
			this.afterburner$ours = strength;
		} else {
			cir.setReturnValue(strength);
		}
	}

	@Inject(method = "calculateTargetStrength", at = @At("RETURN"))
	private void afterburner$check(Level level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
		if (WireReads.CHECK && this.afterburner$ours >= 0) {
			WireReads.checked(pos, "power", this.afterburner$ours, cir.getReturnValueI());
			this.afterburner$ours = -1;
		}
	}
}
