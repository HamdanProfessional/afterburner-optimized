package com.afterburner.client.mixin.particle;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a particle was last tick, for {@link ParticleCullingMixin}. */
@Mixin(Particle.class)
public interface ParticleAccessor {
	@Accessor("xo")
	double afterburner$xo();

	@Accessor("yo")
	double afterburner$yo();

	@Accessor("zo")
	double afterburner$zo();
}
