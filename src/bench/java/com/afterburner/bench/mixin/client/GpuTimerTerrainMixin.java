package com.afterburner.bench.mixin.client;

import com.afterburner.bench.client.GpuTimers;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks where a terrain layer's draws end, for {@link GpuTimers} (the chunk batcher marks where each starts; with it off
 * the time goes to the part of the frame the layer is drawn in).
 */
@Mixin(LevelRenderer.class)
public class GpuTimerTerrainMixin {
	@Inject(method = "renderSectionLayer", at = @At("RETURN"))
	private void afterburner$after(CallbackInfo ci) {
		GpuTimers.mark("level, not terrain");
	}
}
