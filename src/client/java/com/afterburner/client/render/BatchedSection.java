package com.afterburner.client.render;

/**
 * A chunk section, with where each of its layers is in its region ({@link ChunkBatcher}); null for a layer not stored.
 * <p>
 * It also remembers, per layer, when its mesh last changed and what the batcher's frame last saw of it, so the frame
 * only looks a section up again when it actually changed ({@link ChunkBatcher} keeps its draws of the frames before).
 */
public interface BatchedSection {
	ChunkBatcher.SectionMesh[] afterburner$meshes();

	/** The section got a new mesh for this layer (or none). Render thread only. */
	void afterburner$meshChanged(int layer);

	/** Counts the mesh changes of this layer; any difference means the frame's draws of it are out of date. */
	int afterburner$meshVersion(int layer);

	/** The mesh version the frame's draws of this layer were looked up at. */
	int afterburner$builtVersion(int layer);

	void afterburner$built(int layer, int version);

	/** The frame build whose draws of this layer include the section, or 0. */
	int afterburner$frameAt(int layer);

	/** Its index in that build's sections, -1 if it had nothing to draw, or {@link ChunkBatcher#SWAPPING} while it's being added. */
	int afterburner$frameIndex(int layer);

	void afterburner$inFrame(int layer, int stamp, int index);

	/** The shared list stamp the section was last seen in ({@link ChunkBatcher}'s once-per-frame visible-list diff). */
	int afterburner$listStamp();

	void afterburner$listStamp(int stamp);

	/**
	 * Whether the section's compiled state has nothing for this layer. Cached per compiled state, so the hot path is a
	 * byte read instead of a set lookup. Render thread only.
	 */
	boolean afterburner$isEmpty(int layer);
}
