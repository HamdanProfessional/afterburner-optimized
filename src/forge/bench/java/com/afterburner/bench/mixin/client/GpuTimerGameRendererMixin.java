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
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
	private void afterburner$beforeGui(CallbackInfo ci) {
		GpuTimers.mark("gui");
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V", shift = At.Shift.AFTER))
	private void afterburner$afterGui(CallbackInfo ci) {
		GpuTimers.mark("after gui");
	}
}
