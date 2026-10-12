package com.afterburner.client.mixin.render;

import com.afterburner.client.render.OffscreenBuilds;
import com.afterburner.client.render.OffscreenSection;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After the sections on screen went to the builders, hands them off-screen ones too (see {@link OffscreenBuilds}).
 * Chunks or light data arriving (which mark sections for building) and sections built for the first time may let more
 * sections be built.
 */
@Mixin(LevelRenderer.class)
public class OffscreenBuildMixin {
	@Shadow
	private @Nullable ClientLevel level;
	@Shadow
	@Final
	private SectionOcclusionGraph sectionOcclusionGraph;
	@Shadow
	private @Nullable SectionRenderDispatcher sectionRenderDispatcher;

	@Inject(method = {"setSectionDirty(IIIZ)V", "onChunkLoaded"}, at = @At("HEAD"))
	private void afterburner$chunkOrLight(CallbackInfo ci) {
		OffscreenBuilds.changed();
	}

	@Inject(method = "addRecentlyCompiledSection", at = @At("HEAD"))
	private void afterburner$built(SectionRenderDispatcher.RenderSection section, CallbackInfo ci) {
		if (((OffscreenSection) section).afterburner$firstBuild()) OffscreenBuilds.changed();
	}

	@Inject(method = "compileSections", at = @At("TAIL"))
	private void afterburner$buildOffscreen(Camera camera, CallbackInfo ci) {
		if (level != null && sectionRenderDispatcher != null) {
			OffscreenBuilds.schedule(sectionOcclusionGraph, sectionRenderDispatcher, level, camera.getPosition());
		}
	}
}
