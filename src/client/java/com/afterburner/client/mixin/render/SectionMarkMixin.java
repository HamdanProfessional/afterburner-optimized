package com.afterburner.client.mixin.render;

import com.afterburner.client.render.LightWaitSection;
import com.afterburner.client.render.SectionScans;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells {@link SectionScans} when a section is marked for a rebuild, and when a build of it has or had block entities;
 * and remembers when its light was last found off.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class SectionMarkMixin implements LightWaitSection {
	@Unique
	private int afterburner$lightOffAt;

	@Override
	public int afterburner$lightOffAt() {
		return afterburner$lightOffAt;
	}

	@Override
	public void afterburner$setLightOffAt(int stamp) {
		afterburner$lightOffAt = stamp;
	}

	@Shadow
	public abstract SectionRenderDispatcher.CompiledSection getCompiled();

	@Inject(method = "setDirty", at = @At("HEAD"))
	private void afterburner$marked(boolean playerChanged, CallbackInfo ci) {
		SectionScans.markedForRebuild();
	}

	@Inject(method = "reset", at = @At("HEAD"))
	private void afterburner$reset(CallbackInfo ci) {
		SectionScans.markedForRebuild();
		if (!getCompiled().getRenderableBlockEntities().isEmpty()) SectionScans.blockEntitiesChanged();
	}

	/** A new build with block entities replaces their list even when the old one had some too. */
	@Inject(method = "setCompiled", at = @At("HEAD"))
	private void afterburner$built(SectionRenderDispatcher.CompiledSection compiled, CallbackInfo ci) {
		if (!compiled.getRenderableBlockEntities().isEmpty() || !getCompiled().getRenderableBlockEntities().isEmpty()) {
			SectionScans.blockEntitiesChanged();
		}
	}
}
