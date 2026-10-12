package com.afterburner.client.render;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.atomic.LongAdder;

/** The rule of {@link com.afterburner.client.mixin.render.LeafCullingMixin}, and how many faces it left out, for benchmark reports. */
public final class LeafCulling {
	private static final LongAdder LEFT_OUT = new LongAdder();

	private LeafCulling() {
	}

	/**
	 * Whether a leaf block's face that vanilla draws is still drawn: not if the neighbor on that side is leaves and the block
	 * past it ({@code beyond}) is leaves or solid.
	 */
	public static boolean render(BlockGetter level, BlockState state, BlockState neighbor, BlockPos beyond) {
		if (!(state.getBlock() instanceof LeavesBlock) || !(neighbor.getBlock() instanceof LeavesBlock)) return true;
		BlockState past = level.getBlockState(beyond);
		if (past.getBlock() instanceof LeavesBlock || past.isSolidRender()) {
			LEFT_OUT.increment();
			return false;
		}
		return true;
	}

	public static void reset() {
		LEFT_OUT.reset();
	}

	/** E.g. "Leaf culling: 123,456 leaf faces left out". */
	public static String summary() {
		return "Leaf culling: " + String.format("%,d", LEFT_OUT.sum()) + " leaf faces left out";
	}
}
