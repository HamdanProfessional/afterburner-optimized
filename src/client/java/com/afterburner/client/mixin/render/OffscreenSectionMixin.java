package com.afterburner.client.mixin.render;

import com.afterburner.client.render.OffscreenBuilds;
import com.afterburner.client.render.OffscreenSection;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A section's first build lets the visibility graph reach further, so more off-screen sections may be built (see {@link OffscreenBuilds}). */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public class OffscreenSectionMixin implements OffscreenSection {
	@Unique
	private volatile boolean afterburner$built;

	@Inject(method = "reset", at = @At("HEAD"))
	private void afterburner$unbuilt(CallbackInfo ci) {
		afterburner$built = false;
	}

	@Override
	public boolean afterburner$firstBuild() {
		if (afterburner$built) return false;
		afterburner$built = true;
		return true;
	}
}
