package com.afterburner.client.mixin.render;

import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The frustum's planes and the camera it tests from, for {@link com.afterburner.client.render.RegionFrustum}. */
@Mixin(Frustum.class)
public interface FrustumAccessor {
	@Accessor("intersection")
	FrustumIntersection afterburner$intersection();

	@Accessor("camX")
	double afterburner$camX();

	@Accessor("camY")
	double afterburner$camY();

	@Accessor("camZ")
	double afterburner$camZ();
}
