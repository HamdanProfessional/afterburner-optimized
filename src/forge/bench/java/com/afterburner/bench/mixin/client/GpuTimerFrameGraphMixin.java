package com.afterburner.bench.mixin.client;

import com.afterburner.bench.client.GpuTimers;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Times each pass of the world's frame graph (sky, main, clouds, weather, ...) for {@link GpuTimers}. */
@Mixin(FrameGraphBuilder.class)
public class GpuTimerFrameGraphMixin {
	@WrapOperation(method = "execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V",
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;beforeExecutePass(Ljava/lang/String;)V"))
	private void afterburner$beforePass(FrameGraphBuilder.Inspector inspector, String name, Operation<Void> original) {
		GpuTimers.mark("pass " + name);
		original.call(inspector, name);
	}

	@WrapOperation(method = "execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V",
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;afterExecutePass(Ljava/lang/String;)V"))
	private void afterburner$afterPass(FrameGraphBuilder.Inspector inspector, String name, Operation<Void> original) {
		original.call(inspector, name);
		GpuTimers.mark("between passes");
	}
}
