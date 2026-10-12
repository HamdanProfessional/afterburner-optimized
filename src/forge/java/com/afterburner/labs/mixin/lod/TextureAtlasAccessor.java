package com.afterburner.labs.mixin.lod;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** All the block textures, for the far terrain's colors. */
@Mixin(TextureAtlas.class)
public interface TextureAtlasAccessor {
	@Accessor("texturesByName")
	Map<Identifier, TextureAtlasSprite> afterburner$texturesByName();
}
