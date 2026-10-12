package com.afterburner.bench.mixin.client;

import com.afterburner.bench.client.GpuTimers;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Starts and ends each frame for {@link GpuTimers}. */
@Mixin(Minecraft.class)
public class GpuTimerMinecraftMixin {
	@Inject(method = "renderFrame", at = @At("HEAD"))
	private void afterburner$frameStart(boolean advanceGameTime, CallbackInfo ci) {
		GpuTimers.frameStart();
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"))
	private void afterburner$frameEnd(boolean advanceGameTime, CallbackInfo ci) {
		GpuTimers.frameEnd();
	}
}
