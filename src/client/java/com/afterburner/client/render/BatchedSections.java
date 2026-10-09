package com.afterburner.client.render;

/** Added to vanilla's per-section chunk draws: the batched draws that replace them this frame. */
public interface BatchedSections {
	void afterburner$setFrame(ChunkBatcher.Frame frame);
}
