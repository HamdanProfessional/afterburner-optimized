package com.afterburner.ai;

import org.jspecify.annotations.Nullable;

import java.util.Optional;

/** Added to the point-of-interest storage: its sections looked up by chunk column instead of one by one. */
public interface PoiColumns {
	/** Vanilla's lookup of one section, which loads the chunk's points of interest if they aren't yet. */
	Optional<?> afterburner$getOrLoad(long sectionKey);

	/**
	 * The sections of a chunk column, indexed from the lowest one, the same objects vanilla's lookup gives. Null if the
	 * column hasn't been loaded; a slot is null if vanilla doesn't have that section either.
	 */
	Optional<?> @Nullable [] afterburner$column(long chunkKey);

	int afterburner$minSection();

	int afterburner$sectionCount();
}
