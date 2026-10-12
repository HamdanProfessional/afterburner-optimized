package com.afterburner.labs.lod;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * Builds a sheet one level up from the four below it. Each voxel there stands for 2 x 2 x 2 below: solid if at least half
 * of them are (as the most common block of the upper ones, else the lower ones), else water if there's water and water and
 * solid make half, else air, as bright as its brightest air. Worker thread only.
 */
final class LodMip {
	private static long[] runs = new long[64];

	private LodMip() {
	}

	/**
	 * The parent sheet with the quadrant ({@code qx}, {@code qz}: 0 or 1) the child covers made again from it; the rest is
	 * the old parent's, and so is a column whose four below aren't all there (land made from the seed at this level stays
	 * until what's below covers it).
	 */
	static LodSheet quadrant(LodWorld world, int childLevel, LodSheet child, @Nullable LodSheet oldParent, int qx, int qz) {
		LodSheet parent = new LodSheet();
		if (oldParent != null) {
			System.arraycopy(oldParent.columns, 0, parent.columns, 0, LodSheet.COLUMNS);
			System.arraycopy(oldParent.biomes, 0, parent.biomes, 0, LodSheet.COLUMNS);
		}
		int childHeight = world.heightAt(childLevel), parentHeight = world.heightAt(childLevel + 1);
		int aboveTop = LodSheet.air(world.skyLight ? 15 : 0, 0);
		for (int pz = 0; pz < 8; pz++) {
			for (int px = 0; px < 8; px++) {
				int c = pz * 2 * 16 + px * 2;
				long[] a = child.columns[c], b = child.columns[c + 1], d = child.columns[c + 16], e = child.columns[c + 17];
				int out = (qz * 8 + pz) * 16 + qx * 8 + px;
				if (a == null || b == null || d == null || e == null) continue;
				parent.biomes[out] = child.biomes[c];
				parent.columns[out] = column(world::kind, new long[][]{a, b, d, e}, childHeight, parentHeight, aboveTop);
			}
		}
		return parent;
	}

	/** What a block state id is to the far terrain ({@link LodWorld#kind}); an interface so a column can be tested alone. */
	interface Kinds {
		byte kind(int state);
	}

	private static final int[] UPPER = new int[4], LOWER = new int[4], WATER = new int[8];

	/**
	 * One parent column from its four children. A parent voxel stands for two child layers; where all four children stay
	 * in one run for many layers (the rock under the surface, the air above), the voxels there all get the same value,
	 * so it's worked out once per stretch, not per voxel.
	 */
	static long[] column(Kinds world, long[][] children, int childHeight, int parentHeight, int aboveTop) {
		int[] at = new int[4];
		int[] whats = new int[8];
		int count = 0, runWhat = -1;
		int y = 0;
		while (y < parentHeight) {
			for (int layer = 0; layer < 2; layer++) {
				int h = y * 2 + layer;
				for (int j = 0; j < 4; j++) whats[layer * 4 + j] = childWhat(children[j], at, j, h, childHeight, aboveTop);
			}
			int what = combine(world, whats);
			if (what != runWhat) {
				if (runWhat >= 0) {
					if (count == runs.length) runs = Arrays.copyOf(runs, count * 2);
					runs[count++] = LodSheet.run(runWhat, y);
				}
				runWhat = what;
			}
			// The first child layer where some child may change: up to it, both layers of each parent voxel read the
			// same four values as the upper layer just read.
			int h = y * 2 + 1, end = Integer.MAX_VALUE;
			for (int j = 0; j < 4; j++) end = Math.min(end, runEnd(children[j], at[j], h, childHeight));
			int last = end == Integer.MAX_VALUE ? parentHeight - 1 : Math.min(parentHeight - 1, (end - 2) / 2);
			if (last > y) {
				System.arraycopy(whats, 4, whats, 0, 4);
				int same = combine(world, whats);
				if (same != runWhat) {
					if (count == runs.length) runs = Arrays.copyOf(runs, count * 2);
					runs[count++] = LodSheet.run(runWhat, y + 1);
					runWhat = same;
				}
			}
			y = last + 1;
		}
		if (count == runs.length) runs = Arrays.copyOf(runs, count * 2);
		runs[count++] = LodSheet.run(runWhat, parentHeight);
		return Arrays.copyOf(runs, count);
	}

	private static int childWhat(long[] col, int[] at, int j, int h, int childHeight, int aboveTop) {
		if (h >= childHeight) return aboveTop;
		while (at[j] < col.length && LodSheet.top(col[at[j]]) <= h) at[j]++;
		return at[j] < col.length ? LodSheet.what(col[at[j]]) : aboveTop;
	}

	/** The first child layer above {@code h} where the child's value may differ from its value at {@code h}. */
	private static int runEnd(long[] col, int at, int h, int childHeight) {
		if (h >= childHeight || at >= col.length) return Integer.MAX_VALUE;
		return Math.min(LodSheet.top(col[at]), childHeight);
	}

	/** The parent voxel over 8 child voxels (lower layer first, then upper, 4 each). */
	private static int combine(Kinds world, int[] whats) {
		int solids = 0, waters = 0, upperCount = 0, lowerCount = 0, maxSky = 0, maxBlock = 0;
		for (int i = 0; i < 8; i++) {
			int what = whats[i];
			int state = LodSheet.state(what);
			byte kind = world.kind(state);
			if (LodKinds.solid(kind)) {
				solids++;
				if (i >= 4) UPPER[upperCount++] = state;
				else LOWER[lowerCount++] = state;
			} else if (kind == LodKinds.WATER) {
				WATER[waters++] = state;
			} else {
				maxSky = Math.max(maxSky, LodSheet.sky(what));
				maxBlock = Math.max(maxBlock, LodSheet.blockLight(what));
			}
		}
		if (solids >= 4) {
			int state = mostCommon(UPPER, upperCount, true);
			if (state < 0) state = mostCommon(LOWER, lowerCount, true);
			if (state < 0) state = LodSheet.HIDDEN;
			return state << 8;
		}
		if (waters > 0 && waters + solids >= 4) return mostCommon(WATER, waters, false) << 8;
		return LodSheet.air(maxSky, maxBlock);
	}

	/** The value that comes up most among the first {@code n}, leaving out the hidden placeholder if asked; -1 if none. */
	private static int mostCommon(int[] values, int n, boolean skipHidden) {
		int best = -1, bestCount = 0;
		for (int i = 0; i < n; i++) {
			int v = values[i];
			if (skipHidden && v == LodSheet.HIDDEN) continue;
			int c = 0;
			for (int j = 0; j < n; j++) if (values[j] == v) c++;
			if (c > bestCount) {
				best = v;
				bestCount = c;
			}
		}
		return best;
	}
}
