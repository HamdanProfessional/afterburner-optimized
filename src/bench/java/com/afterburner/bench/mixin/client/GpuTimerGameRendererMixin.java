package com.afterburner.bench.mixin.client;

import com.afterburner.bench.client.GpuTimers;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times the HUD and screens for {@link GpuTimers}. */
@Mixin(GameRenderer.class)
public class GpuTimerGameRendererMixin {
	private static final String GUI = "Lnet/minecraft/client/gui/Gui;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V";

	@Inject(method = "render", at = @At(value = "INVOKE", target = GUI))
	private void afterburner$beforeGui(CallbackInfo ci) {
		GpuTimers.mark("gui");
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = GUI, shift = At.Shift.AFTER))
	private void afterburner$afterGui(CallbackInfo ci) {
		GpuTimers.mark("after gui");
	}
}
