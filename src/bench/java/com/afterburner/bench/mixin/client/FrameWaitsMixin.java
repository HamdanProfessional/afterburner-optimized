package com.afterburner.bench.mixin.client;

import com.afterburner.bench.client.FrameWaits;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times how long each frame the render thread waits for the graphics card, for {@link FrameWaits}. On 1.21.1 that's in
 * showing the frame (swapping buffers), where the driver waits for the frames before; there's no separate submit.
 */
@Mixin(Minecraft.class)
public class FrameWaitsMixin {
	@Inject(method = "runTick", at = @At("HEAD"))
	private void afterburner$frameStart(boolean advanceGameTime, CallbackInfo ci) {
		FrameWaits.frameStart();
	}

	@WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V"))
	private void afterburner$timePresent(Window window, Operation<Void> original) {
		if (!FrameWaits.measuring) {
			original.call(window);
			return;
		}
		long wall = System.nanoTime(), cpu = FrameWaits.cpu();
		original.call(window);
		FrameWaits.present(System.nanoTime() - wall, FrameWaits.cpu() - cpu);
	}
}
