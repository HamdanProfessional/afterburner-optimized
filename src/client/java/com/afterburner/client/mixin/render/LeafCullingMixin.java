package com.afterburner.client.mixin.render;

import com.afterburner.client.render.LeafCulling;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * With see-through leaves (vanilla's default), every face between two leaf blocks is drawn, so a tree is drawn as all its
 * inner faces too. This leaves out a leaf face whose neighbor is leaves and whose neighbor's neighbor, the same way on, is leaves
 * or solid: a face at least two blocks deep in the foliage, which the leaves in front of it hide. Faces one block in, which
 * show through the leaves at a tree's edge, are kept.
 * <p>
 * Only when building chunk sections (culling on): a renderer made per section build, so its own position is safe to use. The
 * section's copy of the world reaches a section past its edges, so two blocks out is always in it. With Fabric API's renderer,
 * which builds chunk sections without coming here, see {@link LeafCullingIndigoMixin}.
 */
@Mixin(ModelBlockRenderer.class)
public class LeafCullingMixin {
	@Shadow
	@Final
	private boolean cull;
	@Unique
	private final BlockPos.MutableBlockPos afterburner$beyond = new BlockPos.MutableBlockPos();

	@ModifyReturnValue(method = "shouldRenderFace", at = @At("RETURN"))
	private boolean afterburner$cullLeaves(boolean render, BlockAndTintGetter level, BlockState state, Direction direction, BlockPos neighborPos) {
		if (!render || !this.cull || !(state.getBlock() instanceof LeavesBlock)) return render;
		return LeafCulling.render(level, state, level.getBlockState(neighborPos), this.afterburner$beyond.setWithOffset(neighborPos, direction));
	}
}
