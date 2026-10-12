package com.afterburner.labs.mixin.lod;

import com.afterburner.labs.lod.Lod;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The far plane (and the culling frustum's, made from the same projection) reaches past the far terrain. */
@Mixin(GameRenderer.class)
public abstract class LodGameRendererMixin {
	@ModifyReturnValue(method = "getDepthFar", at = @At("RETURN"))
	private float afterburner$farTerrainDepth(float far) {
		return Lod.depthFar(far);
	}
}
