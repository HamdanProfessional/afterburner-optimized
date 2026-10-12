package com.afterburner.bench.mixin.client;

import com.afterburner.bench.client.GpuTimers;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Where a frame's GPU work starts and ends, for {@link GpuTimers}. */
@Mixin(Minecraft.class)
public class GpuTimerMinecraftMixin {
	@Inject(method = "runTick", at = @At("HEAD"))
	private void afterburner$frameStart(boolean advanceGameTime, CallbackInfo ci) {
		GpuTimers.frameStart();
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V"))
	private void afterburner$frameEnd(boolean advanceGameTime, CallbackInfo ci) {
		GpuTimers.frameEnd();
	}
}
