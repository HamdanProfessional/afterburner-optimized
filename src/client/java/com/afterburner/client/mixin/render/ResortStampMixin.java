package com.afterburner.client.mixin.render;

import com.afterburner.client.render.BuildStamp;
import com.afterburner.client.render.Stamped;
import com.afterburner.client.render.TranslucentCulling;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Marks the indices a translucent re-sort made with the task's cancel flag, the sort state they come from and their split. */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$ResortTransparencyTask")
public class ResortStampMixin {
	@WrapOperation(method = "doTask", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/MeshData$SortState;buildSortedIndexBuffer(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder;Lcom/mojang/blaze3d/vertex/VertexSorting;)Lcom/mojang/blaze3d/vertex/ByteBufferBuilder$Result;"))
	private ByteBufferBuilder.Result afterburner$stamp(MeshData.SortState state, ByteBufferBuilder builder, VertexSorting sorting,
			Operation<ByteBufferBuilder.Result> original) {
		ByteBufferBuilder.Result result = original.call(state, builder, sorting);
		if (result != null) {
			((Stamped) (Object) result).afterburner$setStamp(new BuildStamp(((CompileTaskAccessor) this).afterburner$cancelled(), 0, state, state.indexType(),
					TranslucentCulling.take(result.byteBuffer())));
		}
		return result;
	}
}
