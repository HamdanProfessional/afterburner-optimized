package com.afterburner.mixin.ai;

import com.afterburner.ai.AiCheck;
import com.afterburner.ai.BlockSearch;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MoveToBlockGoal;
import net.minecraft.world.entity.ai.goal.RemoveBlockGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Skips a block-breaking mob's search when no chunk section nearby can hold the block, see {@link BlockSearch}. */
@Mixin(MoveToBlockGoal.class)
public abstract class MoveToBlockGoalMixin {
	@Shadow
	@Final
	protected PathfinderMob mob;
	@Shadow
	@Final
	private int searchRange;
	@Shadow
	@Final
	private int verticalSearchRange;
	@Shadow
	protected int verticalSearchStart;

	@WrapMethod(method = "findNearestBlock")
	private boolean afterburner$findNearestBlock(Operation<Boolean> original) {
		if (!((Object) this instanceof RemoveBlockGoal goal) || !BlockSearch.plain(goal)) return original.call();
		BlockPos at = mob.blockPosition();
		if (BlockSearch.mightFind(mob.level(), at.getX(), at.getY(), at.getZ(), searchRange, verticalSearchStart, verticalSearchRange,
				((RemoveBlockGoalAccessor) goal).afterburner$block())) {
			return original.call();
		}
		if (!AiCheck.ENABLED) return false;
		boolean found = original.call();
		AiCheck.result("block search", !found, "vanilla found a block to remove near " + at);
		return found;
	}
}
