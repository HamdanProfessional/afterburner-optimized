package com.afterburner.bench.mixin;

import com.afterburner.bench.LightStats;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times the light thread's batches for the benchmarks. Batches of one level run one at a time. */
@Mixin(ThreadedLevelLightEngine.class)
public abstract class LightStatsMixin {
	@Unique
	private long afterburner$batchStart;

	@Inject(method = "runUpdate", at = @At("HEAD"))
	private void afterburner$startBatch(CallbackInfo ci) {
		afterburner$batchStart = System.nanoTime();
	}

	@Inject(method = "runUpdate", at = @At("RETURN"))
	private void afterburner$endBatch(CallbackInfo ci) {
		LightStats.add(System.nanoTime() - afterburner$batchStart);
	}

	@WrapOperation(method = "runUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;runLightUpdates()I"))
	private int afterburner$countSteps(ThreadedLevelLightEngine engine, Operation<Integer> original) {
		int steps = original.call(engine);
		LightStats.addSteps(steps);
		return steps;
	}
}
