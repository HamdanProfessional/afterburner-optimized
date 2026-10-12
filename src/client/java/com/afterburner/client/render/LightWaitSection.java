package com.afterburner.client.render;

/** A section remembers when the rebuild look last found its light off ({@link SectionScans#lightOn}). */
public interface LightWaitSection {
	int afterburner$lightOffAt();

	void afterburner$setLightOffAt(int stamp);
}
