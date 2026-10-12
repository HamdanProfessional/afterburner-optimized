package com.afterburner.bench.mixin.client;

import com.afterburner.bench.client.AutoBench;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ignores the mouse during an automatic benchmark, like {@link BenchKeyboardMixin} the keyboard. */
@Mixin(MouseHandler.class)
public class BenchMouseMixin {
	@Inject(method = {"onButton", "onScroll"}, at = @At("HEAD"), cancellable = true)
	private void afterburner$ignoreDuringBenchmark(CallbackInfo ci) {
		if (AutoBench.ignoresInput(true)) ci.cancel();
	}

	@Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
	private void afterburner$ignoreMoves(CallbackInfo ci) {
		if (AutoBench.ignoresInput(false)) ci.cancel();
	}
}
