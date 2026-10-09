package com.afterburner.mixin.spawn;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** One attribute's value: what the dimension makes it, and the layers over that. For {@link com.afterburner.spawn.SpawnCosts}. */
@Mixin(targets = "net.minecraft.world.attribute.EnvironmentAttributeSystem$ValueSampler")
public interface AttributeSamplerAccessor {
	@Accessor("baseValue")
	Object afterburner$baseValue();

	@Accessor("layers")
	List<?> afterburner$layers();
}
