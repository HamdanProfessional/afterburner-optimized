package com.afterburner.bench.mixin.client;

import com.afterburner.bench.client.AutoBench;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * During an automatic benchmark the game ignores the keyboard (and the mouse, see {@link BenchMouseMixin}), so typing
 * into the window by accident (it takes focus when it opens) can't move the player or open screens and spoil the
 * result. Closing the window still works.
 */
@Mixin(KeyboardHandler.class)
public class BenchKeyboardMixin {
	@Inject(method = {"keyPress", "charTyped"}, at = @At("HEAD"), cancellable = true)
	private void afterburner$ignoreDuringBenchmark(CallbackInfo ci) {
		if (AutoBench.ignoresInput(true)) ci.cancel();
	}
}
