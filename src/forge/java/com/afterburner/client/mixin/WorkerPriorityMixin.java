package com.afterburner.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The game's background workers (chunk generation, lighting, chunk mesh building) run just below normal priority, so
 * when every core is busy the frames, the game's server and other programs come first. Vanilla already puts the
 * render and server threads above normal on PCs with more than 4 cores; on smaller ones everything was equal. Windows
 * honors this; on Linux Java ignores thread priorities, so nothing changes there.
 */
@Mixin(targets = "net.minecraft.util.Util$2")
public class WorkerPriorityMixin {
	@Inject(method = "onStart", at = @At("HEAD"))
	private void afterburner$lowerPriority(CallbackInfo ci) {
		Thread.currentThread().setPriority(Thread.NORM_PRIORITY - 1);
	}
}
