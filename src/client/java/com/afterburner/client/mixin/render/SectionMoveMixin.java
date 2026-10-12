package com.afterburner.client.mixin.render;

import com.afterburner.client.render.RegionFrustum;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sections are moved to new places as the camera moves: the regions a graph's arrays hold may be out of date then. */
@Mixin(ViewArea.class)
public class SectionMoveMixin {
	@Inject(method = "repositionCamera", at = @At("HEAD"))
	private void afterburner$moved(double x, double z, CallbackInfo ci) {
		RegionFrustum.moved();
	}
}
