package com.afterburner.light;

import net.minecraft.world.level.chunk.DataLayer;
import org.jspecify.annotations.Nullable;

/** What the faster light propagation needs from a light storage (added to it by a mixin). */
public interface LightStorageAccess {
	/** The light data of a section being updated, or null if no light is stored for it. */
	@Nullable DataLayer afterburner$layer(long sectionNode);

	/** Stores a light value, like vanilla's {@code setStoredLevel}. */
	void afterburner$set(long blockNode, int level);
}
