package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedSprites;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Blocks drawn as entities (falling blocks, TNT, blocks in minecarts...) keep their animated textures moving (see {@link AnimatedSprites}). */
@Mixin(BlockModelFeatureRenderer.class)
public class SeenBlockModelMixin {
	@Inject(method = "putQuad", at = @At("HEAD"))
	private static void afterburner$seen(PoseStack.Pose pose, BakedQuad quad, QuadInstance instance, int baseTintColor, int[] tintLayers,
			VertexConsumer buffer, CallbackInfo ci) {
		AnimatedSprites.seen(quad.materialInfo().sprite());
	}
}
