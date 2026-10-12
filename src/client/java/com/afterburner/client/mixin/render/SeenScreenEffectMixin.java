package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedSprites;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The block the camera is inside of, and the fire in front of it while burning, keep moving (see {@link AnimatedSprites}). */
@Mixin(ScreenEffectRenderer.class)
public class SeenScreenEffectMixin {
	@Inject(method = "renderTex", at = @At("HEAD"))
	private static void afterburner$seenInWall(TextureAtlasSprite sprite, PoseStack poseStack, CallbackInfo ci) {
		AnimatedSprites.seen(sprite);
	}

	@ModifyExpressionValue(method = "renderFire", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/resources/model/Material;sprite()Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
	private static TextureAtlasSprite afterburner$seenFire(TextureAtlasSprite sprite) {
		AnimatedSprites.seen(sprite);
		return sprite;
	}
}
