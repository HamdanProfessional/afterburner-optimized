package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedSprites;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The fire in front of the camera while burning keeps moving (see {@link AnimatedSprites}). */
@Mixin(ScreenEffectRenderer.class)
public class SeenScreenEffectMixin {
	@ModifyExpressionValue(method = "submit", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/resources/model/sprite/SpriteGetter;get(Lnet/minecraft/client/resources/model/sprite/SpriteId;)Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
	private TextureAtlasSprite afterburner$seen(TextureAtlasSprite sprite) {
		AnimatedSprites.seen(sprite);
		return sprite;
	}
}
