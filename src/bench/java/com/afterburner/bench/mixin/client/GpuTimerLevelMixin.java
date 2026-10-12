package com.afterburner.bench.mixin.client;

import com.afterburner.bench.client.GpuTimers;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Times each part of drawing the world (sky, terrain, entities, particles...) for {@link GpuTimers}, as the profiler names them. */
@Mixin(LevelRenderer.class)
public class GpuTimerLevelMixin {
	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V"))
	private void afterburner$part(ProfilerFiller profiler, String name, Operation<Void> original) {
		GpuTimers.mark("level " + name);
		original.call(profiler, name);
	}
}
