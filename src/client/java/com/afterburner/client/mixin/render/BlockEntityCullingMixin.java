package com.afterburner.client.mixin.render;

import com.afterburner.client.render.EntityCulling;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Block entities hidden behind terrain aren't drawn. Those drawn from far away (beacon and end gateway beams and the like) always are. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityCullingMixin {
	@SuppressWarnings("rawtypes")
	@WrapOperation(method = "tryExtractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;shouldRender(Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/phys/Vec3;)Z"))
	private boolean afterburner$occluded(BlockEntityRenderer renderer, BlockEntity blockEntity, Vec3 camera, Operation<Boolean> original,
			@Local(argsOnly = true) boolean isGloballyRendered) {
		return original.call(renderer, blockEntity, camera) && (isGloballyRendered || !EntityCulling.hide(blockEntity, camera, renderer.getViewDistance()));
	}
}
