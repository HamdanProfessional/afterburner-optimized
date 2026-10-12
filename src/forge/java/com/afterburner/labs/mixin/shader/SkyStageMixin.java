package com.afterburner.labs.mixin.shader;

import com.afterburner.labs.shaderpack.game.Shaderpacks;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What the sky is drawing, for the renderStage uniform: the sun, the moon and the End's flash share a pipeline, and so do
 * the sky and the dark disc under the horizon.
 */
@Mixin(SkyRenderer.class)
public class SkyStageMixin {
	@Inject(method = "renderSun", at = @At("HEAD"))
	private void afterburner$sun(CallbackInfo ci) {
		Shaderpacks.skyStage(Shaderpacks.SkyStage.SUN);
	}

	@Inject(method = "renderMoon", at = @At("HEAD"))
	private void afterburner$moon(CallbackInfo ci) {
		Shaderpacks.skyStage(Shaderpacks.SkyStage.MOON);
	}

	@Inject(method = "renderEndFlash", at = @At("HEAD"))
	private void afterburner$endFlash(CallbackInfo ci) {
		Shaderpacks.skyStage(Shaderpacks.SkyStage.CUSTOM);
	}

	@Inject(method = "renderDarkDisc", at = @At("HEAD"))
	private void afterburner$darkDisc(CallbackInfo ci) {
		Shaderpacks.skyStage(Shaderpacks.SkyStage.VOID);
	}

	@Inject(method = {"renderSun", "renderMoon", "renderEndFlash", "renderDarkDisc"}, at = @At("RETURN"))
	private void afterburner$done(CallbackInfo ci) {
		Shaderpacks.skyStage(Shaderpacks.SkyStage.NONE);
	}
}
