package com.afterburner.client.render;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * An occlusion graph's sections in the order vanilla keeps them, in flat arrays beside its linked set, with each
 * section's region ({@link RegionFrustum#key}): walking these is reading memory in order, where the set's nodes, their
 * sections and their boxes are all over the heap (most of what vanilla's pass over the graph cost).
 */
public interface RegionList {
	/** A section joined the graph (the thread building the graph only). */
	void afterburner$append(SectionRenderDispatcher.RenderSection section);

	int afterburner$count();

	SectionRenderDispatcher.RenderSection[] afterburner$sections();

	long[] afterburner$keys();

	/** Each section's corner, x y z: its box is the 16 blocks from there. */
	int[] afterburner$origins();

	/**
	 * Reads every section's corner again if sections were moved since they were read (the camera crossed into another
	 * section): the graph holds the same sections, now elsewhere, as vanilla's own test of their boxes would see.
	 * Render thread, on the graph it draws from.
	 */
	void afterburner$refresh();
}
