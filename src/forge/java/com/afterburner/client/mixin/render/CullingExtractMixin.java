package com.afterburner.client.mixin.render;

import com.afterburner.client.render.EntityCulling;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells {@link EntityCulling} when the frame's entities and block entities are gathered for drawing. */
@Mixin(LevelExtractor.class)
public class CullingExtractMixin {
	@Inject(method = "extract", at = @At("HEAD"))
	private void afterburner$startFrame(CallbackInfo ci) {
		EntityCulling.startFrame();
	}

	@Inject(method = "extractVisibleEntities", at = @At("HEAD"))
	private void afterburner$entities(CallbackInfo ci) {
		EntityCulling.phase(EntityCulling.ENTITIES);
	}

	@Inject(method = "extractVisibleBlockEntities", at = @At("HEAD"))
	private void afterburner$blockEntities(CallbackInfo ci) {
		EntityCulling.phase(EntityCulling.BLOCK_ENTITIES);
	}

	@Inject(method = {"extractVisibleEntities", "extractVisibleBlockEntities"}, at = @At("RETURN"))
	private void afterburner$done(CallbackInfo ci) {
		EntityCulling.phase(EntityCulling.NONE);
	}
}
