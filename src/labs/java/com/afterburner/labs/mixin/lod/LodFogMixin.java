package com.afterburner.labs.mixin.lod;

import com.afterburner.labs.lod.Lod;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The render distance fog moves out to the far terrain's edge. */
@Mixin(FogRenderer.class)
public abstract class LodFogMixin {
	@Inject(method = "setupFog", at = @At("RETURN"))
	private static void afterburner$farTerrainFog(Camera camera, FogRenderer.FogMode mode, float renderDistance, boolean thickFog, float partialTick,
			CallbackInfo ci) {
		Lod.adjustFog(camera, mode, renderDistance, thickFog);
	}
}
