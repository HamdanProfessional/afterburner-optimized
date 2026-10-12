package com.afterburner.labs.mixin.lod;

import com.afterburner.labs.lod.Lod;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The render distance fog moves out to the far terrain's edge. */
@Mixin(FogRenderer.class)
public abstract class LodFogMixin {
	@Inject(method = "setupFog", at = @At("RETURN"))
	private void afterburner$farTerrainFog(Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker, float darkenWorldAmount, ClientLevel level,
			CallbackInfoReturnable<FogData> cir) {
		Lod.adjustFog(cir.getReturnValue(), camera, renderDistanceInChunks);
	}
}
