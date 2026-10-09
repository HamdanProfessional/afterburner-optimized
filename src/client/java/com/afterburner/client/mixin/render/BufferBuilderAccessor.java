package com.afterburner.client.mixin.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BufferBuilder.class)
public interface BufferBuilderAccessor {
	/** How many vertices it has been given so far. */
	@Accessor("vertices")
	int afterburner$vertices();
}
