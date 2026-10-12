package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedSprites;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block textures drawn in screens and on the HUD (the nether portal overlay, fluids in mod screens) keep moving (see {@link AnimatedSprites}). */
@Mixin(GuiGraphics.class)
public class SeenGuiSpriteMixin {
	@Inject(method = "blit(IIIIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V", at = @At("HEAD"))
	private void afterburner$seen(int x, int y, int z, int width, int height, TextureAtlasSprite sprite, CallbackInfo ci) {
		AnimatedSprites.seen(sprite);
	}

	@Inject(method = "blit(IIIIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;FFFF)V", at = @At("HEAD"))
	private void afterburner$seenTinted(int x, int y, int z, int width, int height, TextureAtlasSprite sprite, float red, float green, float blue,
			float alpha, CallbackInfo ci) {
		AnimatedSprites.seen(sprite);
	}
}
