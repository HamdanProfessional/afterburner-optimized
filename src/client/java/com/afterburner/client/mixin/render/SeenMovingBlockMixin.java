package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedSprites;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Blocks pushed by pistons keep their animated textures moving (see {@link AnimatedSprites}). */
@Mixin(MovingBlockFeatureRenderer.class)
public class SeenMovingBlockMixin {
	@Inject(method = "putBakedQuad", at = @At("HEAD"))
	private void afterburner$seen(PoseStack poseStack, float x, float y, float z, BakedQuad quad, QuadInstance instance, ChunkSectionLayer layer,
			int outlineColor, CallbackInfo ci) {
		AnimatedSprites.seen(quad.materialInfo().sprite());
	}
}
