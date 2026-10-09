package com.afterburner.client.render;

/** Added to section build results: where {@link ChunkRegions#moveToRegion} moved the vertices to. */
public interface RegionResults {
	/** The section's place in its region ({@link ChunkRegions#slot}), or -1 if its vertices weren't moved. */
	int afterburner$regionSlot();

	void afterburner$setRegionSlot(int slot);

	/** Which region the section is in ({@link ChunkRegions#key}), moved or not. */
	long afterburner$regionKey();

	void afterburner$setRegionKey(long key);
}
