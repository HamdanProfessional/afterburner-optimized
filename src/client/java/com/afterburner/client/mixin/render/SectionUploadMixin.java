package com.afterburner.client.mixin.render;

import com.afterburner.client.render.ChunkBatcher;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;

/** Section meshes go to their region's buffers ({@link ChunkBatcher}) instead of the section's own, through vanilla's upload queue. */
@Mixin(SectionRenderDispatcher.class)
public class SectionUploadMixin {
	@Shadow
	@Final
	private Queue<Runnable> toUpload;
	@Shadow
	private volatile boolean closed;

	@Inject(method = "uploadSectionLayer", at = @At("HEAD"), cancellable = true)
	private void afterburner$toRegion(MeshData mesh, VertexBuffer buffer, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
		if (closed) return;
		Runnable upload = ChunkBatcher.prepare(mesh, buffer);
		if (upload != null) cir.setReturnValue(CompletableFuture.runAsync(upload, toUpload::add));
	}

	@Inject(method = "uploadSectionIndexBuffer", at = @At("HEAD"), cancellable = true)
	private void afterburner$indicesToRegion(ByteBufferBuilder.Result result, VertexBuffer buffer, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
		if (closed) return;
		Runnable upload = ChunkBatcher.prepareIndices(result, buffer);
		if (upload != null) cir.setReturnValue(CompletableFuture.runAsync(upload, toUpload::add));
	}
}
