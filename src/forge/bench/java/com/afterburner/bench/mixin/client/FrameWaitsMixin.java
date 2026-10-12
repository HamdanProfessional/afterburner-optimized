package com.afterburner.bench.mixin.client;

import com.afterburner.bench.client.FrameWaits;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuSurface;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times each frame, and its submit and present, for {@link FrameWaits}. */
@Mixin(Minecraft.class)
public class FrameWaitsMixin {
	@Inject(method = "renderFrame", at = @At("HEAD"))
	private void afterburner$frameStart(boolean advanceGameTime, CallbackInfo ci) {
		FrameWaits.frameStart();
	}

	@WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V"))
	private void afterburner$timeSubmit(CommandEncoder encoder, Operation<Void> original) {
		if (!FrameWaits.measuring) {
			original.call(encoder);
			return;
		}
		long wall = System.nanoTime(), cpu = FrameWaits.cpu();
		original.call(encoder);
		FrameWaits.submit(System.nanoTime() - wall, FrameWaits.cpu() - cpu);
	}

	@WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"))
	private void afterburner$timePresent(GpuSurface surface, Operation<Void> original) {
		if (!FrameWaits.measuring) {
			original.call(surface);
			return;
		}
		long wall = System.nanoTime(), cpu = FrameWaits.cpu();
		original.call(surface);
		FrameWaits.present(System.nanoTime() - wall, FrameWaits.cpu() - cpu);
	}
}
