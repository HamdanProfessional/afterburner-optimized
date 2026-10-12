package com.afterburner.client.mixin.particle;

import com.afterburner.client.render.ParticleCulling;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Starts a frame's particle culling ({@link ParticleCulling#beginFrame}). */
@Mixin(ParticleEngine.class)
public class ParticleFrameMixin {
	@Inject(method = "extract", at = @At("HEAD"))
	private void afterburner$beginFrame(CallbackInfo ci) {
		ParticleCulling.beginFrame();
	}
}
