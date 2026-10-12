package com.afterburner.client.mixin.render;

import com.afterburner.client.render.RestartablePass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/** Lets the occlusion test end the main render pass for a moment (see {@link RestartablePass}). */
@Mixin(FrontendRenderPass.class)
public class PassRestartMixin implements RestartablePass {
	@Shadow
	@Final
	@Mutable
	private RenderPassBackend backend;
	@Shadow
	@Final
	private Runnable onFinish;
	@Shadow
	@Final
	@Mutable
	private List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colorAttachments;
	@Shadow
	private boolean isClosed;
	@Shadow
	private int pushedDebugGroups;
	@Shadow
	private @Nullable FrontendRenderPipeline boundPipeline;
	@Shadow
	@Final
	private @Nullable GpuBufferSlice[] vertexBuffers;
	@Shadow
	protected @Nullable GpuBuffer indexBuffer;
	@Shadow
	@Final
	protected HashMap<String, Object> uniforms;
	@Shadow
	private boolean constantsPushed;
	@Unique
	private @Nullable RenderPassDescriptor afterburner$descriptor;

	@Override
	public @Nullable RenderPassDescriptor afterburner$descriptor() {
		return afterburner$descriptor;
	}

	@Override
	public void afterburner$setDescriptor(RenderPassDescriptor descriptor) {
		afterburner$descriptor = descriptor;
	}

	@Override
	public boolean afterburner$restart(Runnable between) {
		return afterburner$restart(between, null);
	}

	@Override
	public boolean afterburner$restart(Runnable between, @Nullable RenderPassDescriptor next) {
		RenderPassDescriptor descriptor = next != null ? next : afterburner$descriptor;
		if (isClosed || afterburner$descriptor == null || descriptor == null) return false;
		// Debug groups can't span two passes: closed here and opened again (unnamed) on the next one.
		int groups = pushedDebugGroups;
		for (int i = 0; i < groups; i++) backend.popDebugGroup();
		onFinish.run();
		try {
			between.run();
		} finally {
			List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors = new ArrayList<>();
			for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> color : descriptor.colorAttachments()) {
				colors.add(color == null ? null : new RenderPassDescriptor.Attachment<>(color.textureView(), Optional.empty()));
			}
			RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
			RenderPassDescriptor again = new RenderPassDescriptor(descriptor.label(), colors,
					depth == null ? null : new RenderPassDescriptor.Attachment<>(depth.textureView(), OptionalDouble.empty()), descriptor.renderArea());
			RenderPass fresh = RenderSystem.getDevice().createCommandEncoder().createRenderPass(again);
			PassRestartMixin other = (PassRestartMixin) (Object) fresh;
			backend = other.backend;
			if (next != null) {
				colorAttachments = other.colorAttachments;
				afterburner$descriptor = next;
				boundPipeline = null;
			}
			// The new pass object only lent its backend: this one goes on and is the one closed.
			other.isClosed = true;
			for (int i = 0; i < groups; i++) backend.pushDebugGroup(() -> "Terrain (after occlusion test)");
			FrontendRenderPipeline pipeline = boundPipeline;
			if (pipeline != null) {
				backend.setPipeline(pipeline.backendRenderPipeline());
				uniforms.forEach((name, value) -> {
					int index = pipeline.uniformIndices().getOrDefault(name, -1);
					if (index != -1) backend.setUniform(index, value);
				});
				constantsPushed = false;
			}
			for (int slot = 0; slot < vertexBuffers.length; slot++) {
				if (vertexBuffers[slot] != null) backend.setVertexBuffer(slot, vertexBuffers[slot]);
			}
			indexBuffer = null;
		}
		return true;
	}
}
