package com.afterburner.client.mixin.particle;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Particle.class)
public interface ParticleAccessor {
	@Accessor("x")
	double afterburner$x();

	@Accessor("y")
	double afterburner$y();

	@Accessor("z")
	double afterburner$z();

	@Accessor("xo")
	double afterburner$xo();

	@Accessor("yo")
	double afterburner$yo();

	@Accessor("zo")
	double afterburner$zo();
}
