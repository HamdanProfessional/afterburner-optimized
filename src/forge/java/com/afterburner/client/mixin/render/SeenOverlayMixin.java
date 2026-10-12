package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedSprites;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The block the camera is inside of keeps its texture moving on the overlay (see {@link AnimatedSprites}). */
@Mixin(LevelExtractor.class)
public class SeenOverlayMixin {
	@ModifyExpressionValue(method = "extractPlayerState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/resources/model/sprite/Material$Baked;sprite()Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
	private TextureAtlasSprite afterburner$seen(TextureAtlasSprite sprite) {
		AnimatedSprites.seen(sprite);
		return sprite;
	}
}
