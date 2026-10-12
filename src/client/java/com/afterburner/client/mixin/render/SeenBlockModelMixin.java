package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedSprites;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Blocks drawn outside of chunks (falling blocks, TNT, blocks in minecarts or pushed by pistons, blocks mobs carry) keep
 * their animated textures moving (see {@link AnimatedSprites}). Chunks are built with the same code on other threads,
 * and what they show is read from their meshes instead.
 */
@Mixin(ModelBlockRenderer.class)
public class SeenBlockModelMixin {
	@Inject(method = "putQuadData", at = @At("HEAD"))
	private void afterburner$seen(BlockAndTintGetter level, BlockState state, BlockPos pos, VertexConsumer buffer, PoseStack.Pose pose, BakedQuad quad,
			float shade0, float shade1, float shade2, float shade3, int light0, int light1, int light2, int light3, int overlay, CallbackInfo ci) {
		if (RenderSystem.isOnRenderThread()) AnimatedSprites.seen(quad.getSprite());
	}

	@Inject(method = "renderQuadList", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBulkData(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/renderer/block/model/BakedQuad;FFFFII)V"))
	private static void afterburner$seenModel(PoseStack.Pose pose, VertexConsumer buffer, float red, float green, float blue, List<BakedQuad> quads,
			int light, int overlay, CallbackInfo ci, @Local BakedQuad quad) {
		if (RenderSystem.isOnRenderThread()) AnimatedSprites.seen(quad.getSprite());
	}
}
