package com.afterburner.client.render;

/** Added to vanilla's render sections: the frame whose {@link OcclusionCulling} test last found the section hidden behind nearer terrain, or -1. */
public interface OccludedSection {
	long afterburner$hiddenAt();

	void afterburner$setHiddenAt(long frame);
}
