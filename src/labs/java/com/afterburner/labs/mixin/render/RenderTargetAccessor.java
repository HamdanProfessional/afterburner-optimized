package com.afterburner.labs.mixin.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A render target's textures, swapped for smaller ones while the world is drawn (see WorldScale). */
@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {
	@Accessor("colorTextureId")
	int afterburner$colorTextureId();

	@Accessor("colorTextureId")
	void afterburner$setColorTextureId(int id);

	@Accessor("depthBufferId")
	int afterburner$depthBufferId();

	@Accessor("depthBufferId")
	void afterburner$setDepthBufferId(int id);
}
