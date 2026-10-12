package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedSprites;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Items keep their animated block textures moving (see {@link AnimatedSprites}). */
@Mixin(ItemRenderer.class)
public class SeenItemMixin {
	@Inject(method = "renderQuadList", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBulkData(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/renderer/block/model/BakedQuad;FFFFII)V"))
	private void afterburner$seen(PoseStack poseStack, VertexConsumer buffer, List<BakedQuad> quads, ItemStack stack, int light, int overlay,
			CallbackInfo ci, @Local BakedQuad quad) {
		AnimatedSprites.seen(quad.getSprite());
	}
}
