package com.afterburner.client.render;

/**
 * Added to entities and block entities: the frame whose occlusion test last found it hidden behind terrain (-1 if the
 * last test saw it), and where it was then (see {@link EntityCulling}).
 */
public interface OccludedObject {
	long afterburner$hiddenAt();

	/** Squared distance from where it was when found hidden. */
	double afterburner$movedSq(double x, double y, double z);

	void afterburner$setHiddenAt(long frame, double x, double y, double z);
}
