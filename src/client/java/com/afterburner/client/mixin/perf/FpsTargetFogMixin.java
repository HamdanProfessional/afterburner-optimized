package com.afterburner.client.mixin.perf;

import com.afterburner.client.perf.FpsTarget;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** The render distance fog comes in to where {@link FpsTarget} draws chunks to. */
@Mixin(LevelRenderer.class)
public class FpsTargetFogMixin {
	@ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/FogRenderer;setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V"),
			index = 2)
	private float afterburner$fogDistance(float renderDistance) {
		return Math.max(FpsTarget.fogDistance((int) renderDistance >> 4) * 16.0F, 32.0F);
	}
}
