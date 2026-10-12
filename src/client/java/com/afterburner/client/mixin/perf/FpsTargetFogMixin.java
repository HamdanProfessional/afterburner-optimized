package com.afterburner.client.mixin.perf;

import com.afterburner.client.perf.FpsTarget;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The render distance fog comes in to where {@link FpsTarget} draws chunks to. 1.21.1 sets up the fog once for the sky
 * and once for the terrain, so the distance parameter is brought down here rather than at either call site (the sky
 * one is inside a lambda in LevelRenderer.renderLevel).
 */
@Mixin(FogRenderer.class)
public class FpsTargetFogMixin {
	@ModifyVariable(method = "setupFog", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private static float afterburner$fogDistance(float renderDistance) {
		if (FpsTarget.drawCap() == FpsTarget.NONE) return renderDistance;
		return FpsTarget.fogDistance((int) renderDistance >> 4) * 16.0F;
	}
}
