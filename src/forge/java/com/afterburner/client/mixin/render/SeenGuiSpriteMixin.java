package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedSprites;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block textures drawn in screens and on the HUD (the nether portal overlay, fluids in mod screens) keep moving (see {@link AnimatedSprites}). */
@Mixin(GuiGraphicsExtractor.class)
public class SeenGuiSpriteMixin {
	@Inject(method = "blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;IIIII)V",
			at = @At("HEAD"))
	private void afterburner$seen(RenderPipeline pipeline, TextureAtlasSprite sprite, int x, int y, int width, int height, int color, CallbackInfo ci) {
		AnimatedSprites.seen(sprite);
	}
}
