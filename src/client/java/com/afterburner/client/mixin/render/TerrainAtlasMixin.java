package com.afterburner.client.mixin.render;

import com.afterburner.client.render.TerrainExtras;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Tells {@link TerrainExtras} where the block atlas's textures are, for their middles. */
@Mixin(TextureAtlas.class)
public class TerrainAtlasMixin {
	@Shadow
	@Final
	private Identifier location;
	@Shadow
	private int width;
	@Shadow
	private int height;
	@Shadow
	private List<TextureAtlasSprite> sprites;

	@Inject(method = "upload", at = @At("RETURN"))
	private void afterburner$findSprites(SpriteLoader.Preparations preparations, CallbackInfo ci) {
		if (location.equals(TextureAtlas.LOCATION_BLOCKS)) TerrainExtras.atlasUploaded(List.copyOf(sprites), width, height);
	}
}
