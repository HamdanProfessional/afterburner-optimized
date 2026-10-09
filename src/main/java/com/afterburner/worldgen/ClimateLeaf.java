package com.afterburner.worldgen;

/** Added to vanilla's biome tree leaves: their number in {@link ClimateIndex}. */
public interface ClimateLeaf {
	int afterburner$index();

	void afterburner$setIndex(int index);
}
