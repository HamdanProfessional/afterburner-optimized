package com.afterburner.mixin.explosion;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EntityBasedExplosionDamageCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The entity whose resistance rules an explosion uses, for {@link com.afterburner.explosion.ExplosionRays#cacheable}. */
@Mixin(EntityBasedExplosionDamageCalculator.class)
public interface EntityCalculatorAccessor {
	@Accessor("source")
	Entity afterburner$source();
}
