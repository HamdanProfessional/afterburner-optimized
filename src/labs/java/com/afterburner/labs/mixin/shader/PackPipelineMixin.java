package com.afterburner.labs.mixin.shader;

import com.afterburner.labs.shaderpack.game.PackPass;
import com.afterburner.labs.shaderpack.game.Shaderpacks;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In a pack pass, each pipeline the game sets is swapped for the pack's version of it; draws the pack has no program for are
 * left out.
 */
@Mixin(FrontendRenderPass.class)
public class PackPipelineMixin implements PackPass {
	@Unique
	private PackPass.@Nullable Kind afterburner$packKind;
	@Unique
	private boolean afterburner$skip;

	@Override
	public PackPass.@Nullable Kind afterburner$packKind() {
		return afterburner$packKind;
	}

	@Override
	public void afterburner$setPackKind(PackPass.@Nullable Kind kind) {
		afterburner$packKind = kind;
	}

	@Inject(method = "setPipeline", at = @At("HEAD"), cancellable = true)
	private void afterburner$packPipeline(CompiledRenderPipeline pipeline, CallbackInfo ci) {
		PackPass.Kind kind = afterburner$packKind;
		if (kind == null) return;
		CompiledRenderPipeline replacement = Shaderpacks.pipelineFor(pipeline, kind);
		afterburner$skip = replacement == null;
		if (replacement == pipeline) return;
		ci.cancel();
		if (replacement == null) return;
		((RenderPass) (Object) this).setPipeline(replacement);
		Shaderpacks.drawStage((RenderPass) (Object) this, pipeline, kind);
	}

	/** In a pack pass, the game's textures are sampled sharp (see {@link Shaderpacks#sampler}). */
	@Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V",
			at = @At("HEAD"), cancellable = true)
	private void afterburner$sharpTextures(String name, @Nullable GpuTextureView view, @Nullable GpuSampler sampler, CallbackInfo ci) {
		if (afterburner$packKind == null) return;
		GpuSampler replacement = Shaderpacks.sampler(name, sampler);
		if (replacement == sampler) return;
		ci.cancel();
		((RenderPass) (Object) this).setUniform(name, view, replacement);
	}

	@Inject(method = {
			"draw(IIII)V",
			"drawIndexed(IIIII)V",
			"multiDrawIndexed(Ljava/nio/IntBuffer;III)V",
			"multiDrawIndexed(Lorg/lwjgl/PointerBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V",
			"drawIndexedIndirect(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;I)V",
			"drawMultipleIndexed(Ljava/util/Collection;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;Lcom/mojang/renderpearl/api/pipeline/IndexType;Ljava/util/Collection;Ljava/lang/Object;)V",
			"multiDraw(Ljava/nio/IntBuffer;III)V",
			"multiDraw(Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V",
			"drawIndirect(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;I)V"
	}, at = @At("HEAD"), cancellable = true)
	private void afterburner$skipDraw(CallbackInfo ci) {
		if (afterburner$skip && afterburner$packKind != null) ci.cancel();
	}
}
