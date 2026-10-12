package com.afterburner.client.mixin.render;

import com.afterburner.client.render.OccludedSection;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Remembers what the last occlusion test said about the section (see {@link com.afterburner.client.render.OcclusionCulling}). */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public class OcclusionSectionMixin implements OccludedSection {
	@Unique
	private long afterburner$hiddenAt = -1;

	@Override
	public long afterburner$hiddenAt() {
		return afterburner$hiddenAt;
	}

	@Override
	public void afterburner$setHiddenAt(long frame) {
		afterburner$hiddenAt = frame;
	}
}
