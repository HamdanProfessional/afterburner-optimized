package com.afterburner.bench.mixin.client;

import com.afterburner.bench.client.GpuTimers;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times chunk drawing, which runs inside the frame graph's main pass, for {@link GpuTimers}. */
@Mixin(ChunkSectionsToRender.class)
public class GpuTimerTerrainMixin {
	@Inject(method = "renderGroup", at = @At("HEAD"))
	private void afterburner$before(ChunkSectionLayerGroup group, RenderPass pass, GpuSampler sampler, GpuTextureView atlas, boolean wireframe, CallbackInfo ci) {
		GpuTimers.mark("terrain " + group.name().toLowerCase());
	}

	@Inject(method = "renderGroup", at = @At("RETURN"))
	private void afterburner$after(ChunkSectionLayerGroup group, RenderPass pass, GpuSampler sampler, GpuTextureView atlas, boolean wireframe, CallbackInfo ci) {
		GpuTimers.mark("pass main, not terrain");
	}
}
