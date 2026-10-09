package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedSprites;
import com.afterburner.client.render.TrackedAnimation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.texture.SpriteContents;
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

/** Ties the block atlas's animations to their textures, and finds where those are (see {@link AnimatedSprites}). */
@Mixin(TextureAtlas.class)
public class AnimatedAtlasMixin {
	@Shadow
	@Final
	private Identifier location;
	@Shadow
	private int width;
	@Shadow
	private int height;
	@Shadow
	private List<TextureAtlasSprite> sprites;

	@WrapOperation(method = "upload", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;createAnimationState(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;I)Lnet/minecraft/client/renderer/texture/SpriteContents$AnimationState;"))
	private SpriteContents.AnimationState afterburner$track(TextureAtlasSprite sprite, GpuBufferSlice ubo, int uboSize,
			Operation<SpriteContents.AnimationState> original) {
		SpriteContents.AnimationState state = original.call(sprite, ubo, uboSize);
		if (state != null && location.equals(TextureAtlas.LOCATION_BLOCKS)) ((TrackedAnimation) state).afterburner$track(sprite.contents());
		return state;
	}

	@Inject(method = "upload", at = @At("RETURN"))
	private void afterburner$findAnimated(SpriteLoader.Preparations preparations, CallbackInfo ci) {
		if (location.equals(TextureAtlas.LOCATION_BLOCKS)) AnimatedSprites.atlasUploaded(sprites, width, height);
	}
}
