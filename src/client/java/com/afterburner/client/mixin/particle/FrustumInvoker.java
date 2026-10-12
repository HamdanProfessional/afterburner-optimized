package com.afterburner.client.mixin.particle;

import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Frustum.class)
public interface FrustumInvoker {
	/** Vanilla's box test, without making a box. */
	@Invoker("cubeInFrustum")
	boolean afterburner$cubeInFrustum(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);
}
