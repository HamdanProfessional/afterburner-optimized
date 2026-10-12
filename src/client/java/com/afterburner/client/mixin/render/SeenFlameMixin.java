package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedSprites;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Burning mobs keep the fire moving (see {@link AnimatedSprites}). */
@Mixin(EntityRenderDispatcher.class)
public class SeenFlameMixin {
	@ModifyExpressionValue(method = "renderFlame", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/resources/model/Material;sprite()Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
	private TextureAtlasSprite afterburner$seen(TextureAtlasSprite sprite) {
		AnimatedSprites.seen(sprite);
		return sprite;
	}
}
