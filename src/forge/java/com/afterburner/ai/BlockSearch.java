package com.afterburner.ai;

import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.goal.RemoveBlockGoal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Mobs that break a kind of block (zombies and turtle eggs) look at every block in a 47x47 area, seven layers high, every
 * 10 to 20 seconds. Each chunk section keeps a list of the blocks it might hold, so this asks the sections first: if none
 * of them can hold the block, the search would find nothing.
 */
public final class BlockSearch {
	/** Goals that use {@link RemoveBlockGoal}'s own test for a target block, which this knows how to answer. */
	private static final ClassValue<Boolean> PLAIN = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			for (Class<?> c = type; c != RemoveBlockGoal.class; c = c.getSuperclass()) {
				try {
					c.getDeclaredMethod("isValidTarget", LevelReader.class, net.minecraft.core.BlockPos.class);
					return false;
				} catch (NoSuchMethodException e) {
					// not declared here, keep looking
				}
			}
			return true;
		}
	};

	private BlockSearch() {
	}

	public static boolean plain(RemoveBlockGoal goal) {
		return PLAIN.get(goal.getClass());
	}

	/**
	 * False only if no block in the area vanilla's {@code MoveToBlockGoal.findNearestBlock} searches can be {@code block},
	 * so the search would find nothing. The area is {@code searchRange - 1} blocks around the mob each way, and the
	 * layers the vertical loop visits, one block down.
	 */
	public static boolean mightFind(Level level, int x, int y, int z, int searchRange, int verticalStart, int verticalRange, Block block) {
		if (level.isDebug() || Blocks.AIR.defaultBlockState().is(block)) return true;
		int minDy = Integer.MAX_VALUE, maxDy = Integer.MIN_VALUE;
		for (int dy = verticalStart; dy <= verticalRange; dy = dy > 0 ? -dy : 1 - dy) {
			minDy = Math.min(minDy, dy);
			maxDy = Math.max(maxDy, dy);
		}
		int r = searchRange - 1;
		if (minDy > maxDy || r < 0) return false;
		int minSection = SectionPos.blockToSectionCoord(y + minDy - 1), maxSection = SectionPos.blockToSectionCoord(y + maxDy - 1);
		for (int cz = SectionPos.blockToSectionCoord(z - r); cz <= SectionPos.blockToSectionCoord(z + r); cz++) {
			for (int cx = SectionPos.blockToSectionCoord(x - r); cx <= SectionPos.blockToSectionCoord(x + r); cx++) {
				ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) continue;
				if (chunk.getClass() != LevelChunk.class) return true;
				for (int sy = minSection; sy <= maxSection; sy++) {
					int index = chunk.getSectionIndexFromSectionY(sy);
					if (index < 0 || index >= chunk.getSectionsCount()) continue;
					LevelChunkSection section = chunk.getSection(index);
					if (!section.hasOnlyAir() && section.maybeHas(state -> state.is(block))) return true;
				}
			}
		}
		return false;
	}
}
