package com.afterburner.client.mixin.render;

import com.afterburner.client.render.EntityCulling;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Only what the game draws in the world is culled (see {@link EntityCulling}): the entities it picks while drawing the
 * level, and the block entities of the sections in view (not the ones drawn from anywhere, like beacons).
 */
@Mixin(LevelRenderer.class)
public class CullingPickMixin {
	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"))
	private boolean afterburner$picking(EntityRenderDispatcher dispatcher, Entity entity, Frustum frustum, double x, double y, double z,
			Operation<Boolean> original) {
		EntityCulling.picking(true);
		try {
			return original.call(dispatcher, entity, frustum, x, y, z);
		} finally {
			EntityCulling.picking(false);
		}
	}

	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", ordinal = 0,
			target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V"))
	private void afterburner$occluded(BlockEntityRenderDispatcher dispatcher, BlockEntity blockEntity, float partialTick, PoseStack poseStack,
			MultiBufferSource buffers, Operation<Void> original, @Local(argsOnly = true) Camera camera) {
		BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(blockEntity);
		if (renderer != null && EntityCulling.hide(blockEntity, camera.getPosition(), renderer.getViewDistance())) return;
		original.call(dispatcher, blockEntity, partialTick, poseStack, buffers);
	}
}
