package com.afterburner.client.mixin.particle;

import com.afterburner.client.render.ParticleCulling;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Only the particles drawn in the world, with this frame's view, are culled (see {@link ParticleCulling}). */
@Mixin(LevelRenderer.class)
public class ParticleFrameMixin {
	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/particle/ParticleEngine;render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;F)V"))
	private void afterburner$cullParticles(ParticleEngine engine, LightTexture light, Camera camera, float partialTick, Operation<Void> original) {
		ParticleCulling.beginFrame((LevelRenderer) (Object) this);
		try {
			original.call(engine, light, camera, partialTick);
		} finally {
			ParticleCulling.endFrame();
		}
	}
}
