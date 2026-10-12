package com.afterburner.client.mixin.render;

import com.afterburner.client.render.LeafCulling;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * With see-through leaves (vanilla's default), every face between two leaf blocks is drawn, so a tree is drawn as all its
 * inner faces too. This leaves out a leaf face whose neighbor is leaves and whose neighbor's neighbor, the same way on, is leaves
 * or solid: a face at least two blocks deep in the foliage, which the leaves in front of it hide. Faces one block in, which
 * show through the leaves at a tree's edge, are kept.
 * <p>
 * On 1.21.1 both vanilla's block renderer (when it culls, as in chunk section builds) and Fabric API's renderer, Indigo, ask
 * {@code Block.shouldRenderFace}, so this is there. A section build's copy of the world reaches a chunk past its edges, so
 * two blocks out is always in it.
 */
@Mixin(Block.class)
public class LeafCullingMixin {
	@ModifyReturnValue(method = "shouldRenderFace", at = @At("RETURN"))
	private static boolean afterburner$cullLeaves(boolean render, BlockState state, BlockGetter level, BlockPos pos, Direction direction,
			BlockPos neighborPos) {
		if (!render || !(state.getBlock() instanceof LeavesBlock)) return render;
		return LeafCulling.render(level, state, level.getBlockState(neighborPos), neighborPos.relative(direction));
	}
}
