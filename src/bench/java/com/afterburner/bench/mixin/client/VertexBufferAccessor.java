package com.afterburner.bench.mixin.client;

import com.mojang.blaze3d.vertex.VertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** How many indices a vertex buffer draws, for the occlusion probe's quad counts. */
@Mixin(VertexBuffer.class)
public interface VertexBufferAccessor {
	@Accessor("indexCount")
	int afterburner$indexCount();
}
