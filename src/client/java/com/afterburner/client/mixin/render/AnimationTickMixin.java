package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedSprites;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureManager.class)
public class AnimationTickMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void afterburner$markVisible(CallbackInfo ci) {
		AnimatedSprites.beginTick();
	}
}
