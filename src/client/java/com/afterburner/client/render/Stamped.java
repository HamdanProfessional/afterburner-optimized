package com.afterburner.client.render;

import org.jspecify.annotations.Nullable;

/** A section build's mesh or sorted index buffer, with what it was built for. */
public interface Stamped {
	@Nullable
	BuildStamp afterburner$stamp();

	void afterburner$setStamp(BuildStamp stamp);
}
