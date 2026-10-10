package com.afterburner.client.mixin.particle;

import com.afterburner.client.render.ParticleCulling;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Particles in view of the camera but only in chunk sections hidden behind terrain are left out, as the terrain there is
 * ({@link ParticleCulling#hidden}). The box tested holds the particle wherever it's drawn this frame (between its last two
 * positions), as big as its quad turned any way.
 */
@Mixin(QuadParticleGroup.class)
public class ParticleCullingMixin {
	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z"))
	private boolean afterburner$cull(Frustum frustum, double x, double y, double z, Operation<Boolean> original,
			@Local SingleQuadParticle particle, @Local(argsOnly = true) float partialTick) {
		if (!original.call(frustum, x, y, z)) return false;
		ParticleAccessor p = (ParticleAccessor) particle;
		double r = particle.getQuadSize(partialTick) * 1.5;
		return !ParticleCulling.hidden(Math.min(p.afterburner$xo(), x) - r, Math.min(p.afterburner$yo(), y) - r, Math.min(p.afterburner$zo(), z) - r,
				Math.max(p.afterburner$xo(), x) + r, Math.max(p.afterburner$yo(), y) + r, Math.max(p.afterburner$zo(), z) + r);
	}
}
