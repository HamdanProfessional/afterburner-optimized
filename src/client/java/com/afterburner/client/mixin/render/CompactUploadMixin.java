package com.afterburner.client.mixin.render;

import com.afterburner.client.render.CompactVertices;
import com.afterburner.client.render.RegionMesh;
import com.afterburner.client.render.TerrainExtras;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.ByteBuffer;

/**
 * Uploads the layers of freshly built sections as {@link CompactVertices}, when they fit: in the extended format when the
 * section was built for a shader pack.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public class CompactUploadMixin {
	@WrapOperation(method = "doTask", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;addSectionBuffersToUberBuffer(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;Lnet/minecraft/client/renderer/chunk/CompiledSectionMesh;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;)Z"))
	private boolean afterburner$compact(SectionRenderDispatcher.RenderSection section, ChunkSectionLayer layer, CompiledSectionMesh mesh,
			@Nullable ByteBuffer vertices, @Nullable ByteBuffer indices, Operation<Boolean> original) {
		if (vertices != null && mesh instanceof RegionMesh regional && regional.afterburner$inRegion()) {
			// The upload is retried until there is room, so the vertices may already have been rewritten.
			int count = vertices.remaining() / 28;
			int format = regional.afterburner$format(layer);
			int @Nullable [] @Nullable [] runs = ((TerrainExtras.Holder) mesh).afterburner$blockRuns();
			if (format == CompactVertices.VANILLA) {
				if (runs != null && CompactVertices.extendedAppliesTo(layer)) {
					if (CompactVertices.convertExtended(vertices, count, regional.afterburner$regionSlot(), runs[layer.ordinal()])) format = CompactVertices.EXTENDED;
				} else if (CompactVertices.appliesTo(layer) && CompactVertices.convert(vertices, count, regional.afterburner$regionSlot())) {
					format = CompactVertices.COMPACT;
				}
				regional.afterburner$setFormat(layer, format);
			}
			if (format == CompactVertices.COMPACT) vertices = vertices.slice(vertices.position(), count * CompactVertices.SIZE);
			boolean uploaded = original.call(section, layer, mesh, vertices, indices);
			// The block runs are only needed until then.
			if (uploaded && runs != null) runs[layer.ordinal()] = null;
			return uploaded;
		}
		return original.call(section, layer, mesh, vertices, indices);
	}
}
