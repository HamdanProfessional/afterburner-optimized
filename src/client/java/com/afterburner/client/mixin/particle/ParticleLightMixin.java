package com.afterburner.client.mixin.particle;

import com.afterburner.client.render.ParticleCulling;
import net.minecraft.client.particle.Particle;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A particle keeps its light while it stays in the same block and nothing on the client changed (see {@link ParticleCulling}). */
@Mixin(Particle.class)
public class ParticleLightMixin {
	@Shadow
	protected double x;
	@Shadow
	protected double y;
	@Shadow
	protected double z;
	@Unique
	private int afterburner$lightX, afterburner$lightY, afterburner$lightZ, afterburner$light;
	@Unique
	private int afterburner$lightEpoch;
	@Unique
	private boolean afterburner$lightKept;

	@Inject(method = "getLightColor", at = @At("HEAD"), cancellable = true)
	private void afterburner$keptLight(float partialTick, CallbackInfoReturnable<Integer> cir) {
		int bx = Mth.floor(this.x), by = Mth.floor(this.y), bz = Mth.floor(this.z);
		if (this.afterburner$lightKept && this.afterburner$lightEpoch == ParticleCulling.lightEpoch
				&& bx == this.afterburner$lightX && by == this.afterburner$lightY && bz == this.afterburner$lightZ) {
			cir.setReturnValue(this.afterburner$light);
			return;
		}
		// Kept when it's been looked up below (at the return), for this block and the world as it is now.
		this.afterburner$lightKept = false;
		this.afterburner$lightX = bx;
		this.afterburner$lightY = by;
		this.afterburner$lightZ = bz;
		this.afterburner$lightEpoch = ParticleCulling.lightEpoch;
	}

	@Inject(method = "getLightColor", at = @At("RETURN"))
	private void afterburner$keepLight(float partialTick, CallbackInfoReturnable<Integer> cir) {
		if (!this.afterburner$lightKept) {
			this.afterburner$light = cir.getReturnValueI();
			this.afterburner$lightKept = true;
		}
	}
}
