package com.afterburner.labs.mixin.shader;

import com.afterburner.labs.shaderpack.game.Gl;
import com.afterburner.labs.shaderpack.game.Shaderpacks;
import com.mojang.blaze3d.platform.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What the game sets that a pack's drawing changes behind its back: the blend state is followed (see Gl's blending), and the
 * framebuffer bound, the game's main one being the pack's while the world is drawn (see Shaderpacks#bindFramebuffer).
 */
@Mixin(GlStateManager.class)
public abstract class PackGlStateMixin {
	@Inject(method = "_enableBlend", at = @At("HEAD"))
	private static void afterburner$enableBlend(CallbackInfo ci) {
		Gl.gameBlend(true);
	}

	@Inject(method = "_disableBlend", at = @At("HEAD"))
	private static void afterburner$disableBlend(CallbackInfo ci) {
		Gl.gameBlend(false);
	}

	@Inject(method = "_blendFunc", at = @At("HEAD"))
	private static void afterburner$blendFunc(int src, int dst, CallbackInfo ci) {
		Gl.gameBlendFunc(src, dst);
	}

	@Inject(method = "_blendFuncSeparate", at = @At("HEAD"))
	private static void afterburner$blendFuncSeparate(int srcColor, int dstColor, int srcAlpha, int dstAlpha, CallbackInfo ci) {
		Gl.gameBlendFuncSeparate(srcColor, dstColor, srcAlpha, dstAlpha);
	}

	@Inject(method = "_glBindFramebuffer", at = @At("HEAD"), cancellable = true)
	private static void afterburner$bindFramebuffer(int target, int framebuffer, CallbackInfo ci) {
		if (Shaderpacks.bindFramebuffer(target, framebuffer)) ci.cancel();
		else Gl.boundByGame(target, framebuffer);
	}
}
