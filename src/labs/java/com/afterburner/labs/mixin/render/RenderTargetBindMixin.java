package com.afterburner.labs.mixin.render;

import com.afterburner.labs.shaderpack.game.Shaderpacks;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A pack's frame drawn smaller keeps its viewport when the game binds its targets (see Shaderpacks#bound). */
@Mixin(RenderTarget.class)
public abstract class RenderTargetBindMixin {
	@Inject(method = "_bindWrite", at = @At("TAIL"))
	private void afterburner$bound(boolean setViewport, CallbackInfo ci) {
		Shaderpacks.bound((RenderTarget) (Object) this);
	}
}
