package com.afterburner.labs.lod;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;

/**
 * What a block is to the far terrain: nothing (air, plants, thin blocks), a solid voxel, water or lava. Water is drawn as an
 * opaque surface. Worker thread only.
 */
final class LodKinds {
	static final byte EMPTY = 0, SOLID = 1, WATER = 2, LAVA = 3;
	/**
	 * What a block is to a shader pack: the far vertex has room for 8 materials, each standing for one of Distant Horizons'
	 * (DH_BLOCK_..., the pack's dhMaterialId; see TranslateTarget): unknown, leaves, lava, water, grass, illuminated, snow, sand.
	 */
	static final int UNKNOWN_SLOT = 0, LEAVES_SLOT = 1, LAVA_SLOT = 2, WATER_SLOT = 3, GRASS_SLOT = 4, LIT_SLOT = 5, SNOW_SLOT = 6, SAND_SLOT = 7;
	/** Per block state id: the kind + 1, 0 until worked out. */
	private static byte[] cache = new byte[0];

	private LodKinds() {
	}

	static byte of(BlockState state) {
		int id = Block.getId(state);
		if (id < 0) return of0(state);
		if (id >= cache.length) cache = java.util.Arrays.copyOf(cache, Math.max(id + 1, Block.BLOCK_STATE_REGISTRY.size()));
		byte k = cache[id];
		if (k == 0) {
			k = (byte) (of0(state) + 1);
			cache[id] = k;
		}
		return (byte) (k - 1);
	}

	/** A block's material slot, given its kind. */
	static int material(BlockState state, byte kind) {
		if (kind == WATER) return WATER_SLOT;
		if (kind == LAVA) return LAVA_SLOT;
		Block block = state.getBlock();
		if (block instanceof LeavesBlock) return LEAVES_SLOT;
		if (state.getLightEmission() > 0) return LIT_SLOT;
		if (block == Blocks.GRASS_BLOCK || block == Blocks.MOSS_BLOCK) return GRASS_SLOT;
		if (block instanceof SnowLayerBlock || block instanceof PowderSnowBlock || block == Blocks.SNOW_BLOCK) return SNOW_SLOT;
		if (state.is(BlockTags.SAND)) return SAND_SLOT;
		return UNKNOWN_SLOT;
	}

	/** Solid, lava or the hidden placeholder: what hides the voxel behind it. */
	static boolean solid(byte kind) {
		return kind == SOLID || kind == LAVA;
	}

	private static byte of0(BlockState state) {
		if (state.isAir()) return EMPTY;
		FluidState fluid = state.getFluidState();
		Block block = state.getBlock();
		if (block instanceof LiquidBlock) return fluid.is(FluidTags.LAVA) ? LAVA : fluid.is(FluidTags.WATER) ? WATER : SOLID;
		if (block instanceof LeavesBlock || block instanceof SnowLayerBlock || block instanceof PowderSnowBlock) return SOLID;
		try {
			if (state.isSolidRender()) return SOLID;
			double volume = 0;
			for (AABB box : state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs()) {
				volume += box.getXsize() * box.getYsize() * box.getZsize();
			}
			if (volume >= 0.4) return SOLID;
		} catch (RuntimeException e) {
			// A block whose shape needs a real world around it: left out.
		}
		if (!fluid.isEmpty()) return fluid.is(FluidTags.LAVA) ? LAVA : WATER;
		return EMPTY;
	}
}
