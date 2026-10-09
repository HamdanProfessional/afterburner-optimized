package com.afterburner.client.mixin.render;

import com.afterburner.client.render.OffscreenBuilds;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After the sections on screen went to the builders, hands them off-screen ones too (see {@link OffscreenBuilds}).
 * Chunks or light data arriving (which mark sections for building) may let more sections be built.
 */
@Mixin(LevelExtractor.class)
public class OffscreenBuildMixin {
	@Shadow
	@Final
	private LevelRenderer levelRenderer;
	@Shadow
	private @Nullable ClientLevel level;
	@Shadow
	private @Nullable SectionUpdateTracker sectionUpdateTracker;

	@Inject(method = {"setSectionRangeDirty", "setSectionDirtyWithNeighbors"}, at = @At("HEAD"))
	private void afterburner$chunkOrLight(CallbackInfo ci) {
		OffscreenBuilds.changed();
	}

	@Inject(method = "extract", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=entities"))
	private void afterburner$buildOffscreen(DeltaTracker deltaTracker, Camera camera, float worldPartialTicks, CallbackInfo ci) {
		if (sectionUpdateTracker != null && level != null) OffscreenBuilds.schedule(levelRenderer, level, sectionUpdateTracker, camera.position());
	}
}
