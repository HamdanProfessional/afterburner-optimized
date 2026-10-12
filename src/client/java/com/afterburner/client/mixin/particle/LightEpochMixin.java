package com.afterburner.client.mixin.particle;

import com.afterburner.client.render.ParticleCulling;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Blocks or light changing on the client (which rebuilds their sections) change the light particles are drawn with (see {@link ParticleCulling}). */
@Mixin(LevelRenderer.class)
public class LightEpochMixin {
	@Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
	private void afterburner$changed(CallbackInfo ci) {
		ParticleCulling.lightEpoch++;
	}
}
