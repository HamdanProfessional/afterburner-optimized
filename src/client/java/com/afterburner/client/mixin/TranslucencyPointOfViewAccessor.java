package com.afterburner.client.mixin;

import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where the camera was relative to a section when it was built: -1, 0 or 1 on each axis. */
@Mixin(TranslucencyPointOfView.class)
public interface TranslucencyPointOfViewAccessor {
	@Accessor("x")
	int afterburner$x();

	@Accessor("y")
	int afterburner$y();

	@Accessor("z")
	int afterburner$z();
}
