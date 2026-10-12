package com.afterburner.client.mixin.render;

import com.afterburner.client.render.BatchedBuffer;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Lets a chunk section's vertex buffer know its section and layer, so its uploads go to the section's region instead. */
@Mixin(VertexBuffer.class)
public class VertexBufferTagMixin implements BatchedBuffer {
	@Unique
	private SectionRenderDispatcher.@Nullable RenderSection afterburner$section;
	@Unique
	private int afterburner$layer = -1;

	@Override
	public SectionRenderDispatcher.@Nullable RenderSection afterburner$section() {
		return afterburner$section;
	}

	@Override
	public int afterburner$layer() {
		return afterburner$layer;
	}

	@Override
	public void afterburner$tag(SectionRenderDispatcher.RenderSection section, int layer) {
		afterburner$section = section;
		afterburner$layer = layer;
	}
}
