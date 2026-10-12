package com.afterburner.mixin.spawn;

import com.afterburner.spawn.SpawnCosts;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A spawn try's cost at its spot, and the mob's after it spawned, without a lookup where no spot has one: see {@link SpawnCosts}. */
@Mixin(NaturalSpawner.SpawnState.class)
public abstract class SpawnStateMixin {
	@WrapOperation(method = "canSpawn", at = @At(value = "INVOKE", target = SpawnCosts.GET_VALUE))
	private Object afterburner$costToTry(EnvironmentAttributeSystem system, EnvironmentAttribute<?> attribute, BlockPos pos, Operation<Object> original,
			@Local(argsOnly = true) Level level) {
		return SpawnCosts.value(level, system, attribute, pos, original);
	}

	@WrapOperation(method = "afterSpawn", at = @At(value = "INVOKE", target = SpawnCosts.GET_VALUE))
	private Object afterburner$costSpawned(EnvironmentAttributeSystem system, EnvironmentAttribute<?> attribute, BlockPos pos, Operation<Object> original,
			@Local(argsOnly = true) Mob mob) {
		return SpawnCosts.value(mob.level(), system, attribute, pos, original);
	}
}
