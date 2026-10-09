package com.afterburner.labs.mixin.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A render target's textures, swapped for smaller ones while the world is drawn (see WorldScale). */
@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {
	@Accessor("colorFormat")
	@Nullable GpuFormat afterburner$colorFormat();

	@Accessor("depthFormat")
	@Nullable GpuFormat afterburner$depthFormat();

	@Accessor("colorTexture")
	@Nullable GpuTexture afterburner$colorTexture();

	@Accessor("colorTexture")
	void afterburner$setColorTexture(@Nullable GpuTexture texture);

	@Accessor("colorTextureView")
	@Nullable GpuTextureView afterburner$colorTextureView();

	@Accessor("colorTextureView")
	void afterburner$setColorTextureView(@Nullable GpuTextureView view);

	@Accessor("depthTexture")
	@Nullable GpuTexture afterburner$depthTexture();

	@Accessor("depthTexture")
	void afterburner$setDepthTexture(@Nullable GpuTexture texture);

	@Accessor("depthTextureView")
	@Nullable GpuTextureView afterburner$depthTextureView();

	@Accessor("depthTextureView")
	void afterburner$setDepthTextureView(@Nullable GpuTextureView view);
}
