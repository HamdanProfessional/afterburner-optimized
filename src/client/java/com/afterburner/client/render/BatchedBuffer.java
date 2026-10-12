package com.afterburner.client.render;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jspecify.annotations.Nullable;

/** A chunk section's vertex buffer, told which section and layer it belongs to ({@link ChunkBatcher}). */
public interface BatchedBuffer {
	@Nullable
	SectionRenderDispatcher.RenderSection afterburner$section();

	int afterburner$layer();

	void afterburner$tag(SectionRenderDispatcher.RenderSection section, int layer);
}
