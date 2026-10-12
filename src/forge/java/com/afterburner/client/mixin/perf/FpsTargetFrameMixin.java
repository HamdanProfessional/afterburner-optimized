package com.afterburner.client.mixin.perf;

import com.afterburner.client.perf.FpsTarget;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.device.GpuSurface;
import net.minecraft.client.FramerateLimiter;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times each frame for {@link FpsTarget}, and the waits that hold frames back on purpose: the FPS limit, and VSync. */
@Mixin(Minecraft.class)
public class FpsTargetFrameMixin {
	@Inject(method = "renderFrame", at = @At("HEAD"))
	private void afterburner$targetFrame(boolean advanceGameTime, CallbackInfo ci) {
		FpsTarget.frame((Minecraft) (Object) this);
	}

	@WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V"))
	private void afterburner$targetLimiter(int fps, Operation<Void> original) {
		if (!FpsTarget.timing()) {
			original.call(fps);
			return;
		}
		long start = System.nanoTime();
		original.call(fps);
		FpsTarget.idle(System.nanoTime() - start);
	}

	@WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"))
	private void afterburner$targetPresent(GpuSurface surface, Operation<Void> original) {
		// Without VSync, a long present is the graphics card being behind: not a wait on purpose.
		if (!FpsTarget.timing() || !((Minecraft) (Object) this).options.enableVsync().get()) {
			original.call(surface);
			return;
		}
		long start = System.nanoTime();
		original.call(surface);
		FpsTarget.idle(System.nanoTime() - start);
	}
}
