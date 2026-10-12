package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedSprites;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Items keep their animated block textures moving (see {@link AnimatedSprites}). */
@Mixin(ItemFeatureRenderer.class)
public class SeenItemMixin {
	@ModifyExpressionValue(method = "prepareMainSubmit", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/resources/model/geometry/BakedQuad;materialInfo()Lnet/minecraft/client/resources/model/geometry/BakedQuad$MaterialInfo;"))
	private BakedQuad.MaterialInfo afterburner$seen(BakedQuad.MaterialInfo material) {
		AnimatedSprites.seen(material.sprite());
		return material;
	}
}
