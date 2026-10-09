package com.afterburner.client.mixin.render;

import com.afterburner.client.render.BatchedSections;
import com.afterburner.client.render.ChunkBatcher;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the batches of {@link ChunkBatcher} instead of one draw per section. */
@Mixin(ChunkSectionsToRender.DrawSeparate.class)
public class DrawSeparateMixin implements BatchedSections {
	@Unique
	private ChunkBatcher.@Nullable Frame afterburner$frame;

	@Override
	public void afterburner$setFrame(ChunkBatcher.Frame frame) {
		afterburner$frame = frame;
	}

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void afterburner$drawBatches(ChunkSectionLayer layer, RenderPass renderPass, @Nullable GpuBuffer defaultIndexBuffer,
			@Nullable IndexType defaultIndexType, @Nullable RenderPipeline pipelineOverride, @Nullable RenderPipeline pipelineOverrideMultidraw,
			CallbackInfo ci) {
		if (afterburner$frame == null) return;
		afterburner$frame.draw(layer, renderPass, defaultIndexBuffer, defaultIndexType, pipelineOverride);
		ci.cancel();
	}
}
