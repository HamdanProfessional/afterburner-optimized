package com.afterburner.client.mixin.render;

import com.afterburner.client.render.OccludedSection;
import com.afterburner.client.render.OcclusionCulling;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Each section remembers the last {@link OcclusionCulling} test that found it hidden; one moved elsewhere starts seen. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public class OcclusionSectionMixin implements OccludedSection {
	@Unique
	private long afterburner$hiddenAt = -1;

	@Inject(method = "reset", at = @At("HEAD"))
	private void afterburner$seen(CallbackInfo ci) {
		afterburner$hiddenAt = -1;
	}

	@Override
	public long afterburner$hiddenAt() {
		return afterburner$hiddenAt;
	}

	@Override
	public void afterburner$setHiddenAt(long frame) {
		afterburner$hiddenAt = frame;
	}
}
