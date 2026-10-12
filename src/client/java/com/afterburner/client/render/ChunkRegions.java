package com.afterburner.client.render;

import net.minecraft.core.SectionPos;

/**
 * Chunk sections are grouped in regions of 8 x 4 x 8 sections (128 x 64 x 128 blocks). Vanilla stores block positions
 * relative to each section's corner and tells the GPU where every section is with a draw call of its own. With the
 * positions stored relative to the region's corner instead, all sections of a region share one position, so they can
 * be drawn with one call.
 * <p>
 * The region size and drawing a region with one multi-draw follow Sodium's render regions (CaffeineMC).
 */
public final class ChunkRegions {
	private ChunkRegions() {
	}

	/** The place of a section in its region, 0 to 255: x, y and z in the region in 3, 2 and 3 bits. */
	public static int slot(int sectionX, int sectionY, int sectionZ) {
		return sectionX & 7 | (sectionY & 3) << 3 | (sectionZ & 7) << 5;
	}

	/** One number per region, from a section's position. */
	public static long key(int sectionX, int sectionY, int sectionZ) {
		return SectionPos.asLong(sectionX >> 3, sectionY >> 2, sectionZ >> 3);
	}
}
