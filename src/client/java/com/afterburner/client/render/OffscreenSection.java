package com.afterburner.client.render;

/** A chunk section that knows whether it was built since it was last reset ({@link OffscreenBuilds}). */
public interface OffscreenSection {
	/** True the first time it's asked after a build since the section was reset (moved or let go of). */
	boolean afterburner$firstBuild();
}
