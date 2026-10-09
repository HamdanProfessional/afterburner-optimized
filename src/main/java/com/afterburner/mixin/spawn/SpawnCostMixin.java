package com.afterburner.mixin.spawn;

import com.afterburner.spawn.SpawnCosts;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Each mob's spawn cost, counted every tick, without a lookup where no spot has one: see {@link SpawnCosts}. */
@Mixin(NaturalSpawner.class)
public abstract class SpawnCostMixin {
	@WrapOperation(method = "lambda$createState$0", at = @At(value = "INVOKE", target = SpawnCosts.GET_VALUE))
	private static Object afterburner$cost(EnvironmentAttributeSystem system, EnvironmentAttribute<?> attribute, BlockPos pos,
			Operation<Object> original, @Local(argsOnly = true) ServerLevel level) {
		return SpawnCosts.value(level, system, attribute, pos, original);
	}
}
