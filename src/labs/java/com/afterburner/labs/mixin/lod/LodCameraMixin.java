package com.afterburner.labs.mixin.lod;

import com.afterburner.labs.lod.Lod;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The far plane (and the culling frustum's) reaches past the far terrain. */
@Mixin(Camera.class)
public abstract class LodCameraMixin {
	@ModifyExpressionValue(method = "update", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F", ordinal = 0))
	private float afterburner$farTerrainDepth(float far) {
		return Lod.depthFar(far);
	}
}
