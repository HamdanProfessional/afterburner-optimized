package com.afterburner.client.mixin.render;

import com.afterburner.client.render.LeafCulling;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Leaf culling ({@link LeafCullingMixin}) for chunk sections built through Fabric API's renderer, Indigo: it builds every
 * block itself and asks {@code Block.shouldRenderFace} directly, so vanilla's block renderer, where the other mixin is, never
 * comes into it. Indigo's renderer is one per section build, like vanilla's.
 */
@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl")
public class LeafCullingIndigoMixin {
	@Shadow
	private BlockAndTintGetter level;
	@Shadow
	private BlockPos pos;
	@Unique
	private final BlockPos.MutableBlockPos afterburner$beyond = new BlockPos.MutableBlockPos();

	@WrapOperation(method = "shouldCullFace", require = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/Block;shouldRenderFace(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Z"))
	private boolean afterburner$cullLeaves(BlockState state, BlockState neighbor, Direction direction, Operation<Boolean> original) {
		boolean render = original.call(state, neighbor, direction);
		if (!render) return false;
		return LeafCulling.render(level, state, neighbor, afterburner$beyond.setWithOffset(pos, direction).move(direction));
	}
}
