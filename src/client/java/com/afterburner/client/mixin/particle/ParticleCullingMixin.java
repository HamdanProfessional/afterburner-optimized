package com.afterburner.client.mixin.particle;

import com.afterburner.client.render.ParticleCulling;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Particles out of view or behind terrain aren't drawn (see {@link ParticleCulling}). */
@Mixin(ParticleEngine.class)
public class ParticleCullingMixin {
	@WrapOperation(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V"))
	private void afterburner$cull(Particle particle, VertexConsumer buffer, Camera camera, float partialTick, Operation<Void> original) {
		if (!ParticleCulling.skip(particle, partialTick)) original.call(particle, buffer, camera, partialTick);
	}
}
