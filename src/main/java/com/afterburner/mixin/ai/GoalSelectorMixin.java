package com.afterburner.mixin.ai;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.EnumSet;

/** A mob's goals are checked against the kinds of goal it's not allowed to run, every tick; nearly always there are none. */
@Mixin(GoalSelector.class)
public abstract class GoalSelectorMixin {
	/**
	 * @author Hamdan
	 * @reason Without any kind of goal turned off, no goal has one: answer without walking the goal's kinds.
	 */
	@Overwrite
	private static boolean goalContainsAnyFlags(WrappedGoal goal, EnumSet<Goal.Flag> disabledFlags) {
		if (disabledFlags.isEmpty()) return false;
		for (Goal.Flag flag : goal.getFlags()) {
			if (disabledFlags.contains(flag)) return true;
		}
		return false;
	}
}
