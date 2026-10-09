package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedSprites;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Burning mobs keep the fire moving (see {@link AnimatedSprites}). */
@Mixin(FlameFeatureRenderer.class)
public class SeenFlameMixin {
	@ModifyExpressionValue(method = "buildGroup", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/resources/model/sprite/AtlasManager;get(Lnet/minecraft/client/resources/model/sprite/SpriteId;)Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
	private TextureAtlasSprite afterburner$seen(TextureAtlasSprite sprite) {
		AnimatedSprites.seen(sprite);
		return sprite;
	}
}
