package com.afterburner.client.mixin.render;

import com.afterburner.client.render.BuildStamp;
import com.afterburner.client.render.Stamped;
import com.afterburner.client.render.TranslucentCulling;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Marks each mesh a section build made with the build's cancel flag and the section's position. */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$RebuildTask")
public class RebuildStampMixin {
	@WrapOperation(method = "doTask", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/SectionCompiler;compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;"))
	private SectionCompiler.Results afterburner$stamp(SectionCompiler compiler, SectionPos pos, RenderChunkRegion region, VertexSorting sorting,
			SectionBufferBuilderPack pack, Operation<SectionCompiler.Results> original) {
		SectionCompiler.Results results = original.call(compiler, pos, region, sorting, pack);
		AtomicBoolean cancelled = ((CompileTaskAccessor) this).afterburner$cancelled();
		for (Map.Entry<RenderType, MeshData> layer : results.renderedLayers.entrySet()) {
			MeshData.SortState sort = layer.getKey() == RenderType.translucent() ? results.transparencyState : null;
			MeshData mesh = layer.getValue();
			// The translucent layer was sorted in the build, on this thread.
			long split = sort != null ? TranslucentCulling.take(mesh.indexBuffer()) : -1;
			((Stamped) (Object) mesh).afterburner$setStamp(new BuildStamp(cancelled, pos.asLong(), sort, mesh.drawState().indexType(), split));
		}
		return results;
	}
}
