package com.afterburner.client.render;

/**
 * Added to render sections by chunk batching: what the last frame build took from the section, so the next build can
 * copy its draws from that frame instead of looking them all up again ({@link ChunkBatcher.Frame#build}).
 */
public interface BuiltSection {
	/** Goes up whenever the section gets a new mesh, or its mesh moves in the chunk buffers; any thread. */
	int afterburner$meshVersion();

	void afterburner$meshChanged();

	/** The build that last took the section in, or 0 if that build's draws for it can't be copied (fading in, not all uploaded). */
	int afterburner$builtAt();

	/** The section's place in that build's sections, or -1 if it had nothing to draw. */
	int afterburner$builtIndex();

	/** The mesh version that build saw. */
	int afterburner$builtVersion();

	void afterburner$built(int build, int index, int version);

	/** The build that last had the section in its list (drawable or not), so vanilla's sections can be found in it ({@link WideSections}). */
	int afterburner$frameAt();

	/** The section's place in that build's sections, or -1 if it had nothing to draw there. */
	int afterburner$frameIndex();

	void afterburner$inFrame(int build, int index);

	/** The last list taken in place that had the section ({@link ChunkBatcher.Frame#update}). */
	int afterburner$listedAt();

	void afterburner$listed(int list);

	/** Whether the section's mesh has block entities to draw, looked at again only when the mesh changed ({@link BlockEntitySections}). Render thread. */
	boolean afterburner$hasBlockEntities();
}
