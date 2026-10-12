package com.afterburner.client.mixin.render;

import com.afterburner.client.render.TranslucentCulling;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Notes which way each quad of a section's translucent mesh faces when vanilla first sorts it (see
 * {@link TranslucentCulling}). Other sorted meshes (translucent entities, items) aren't a section's and are left alone.
 */
@Mixin(MeshData.class)
public class TranslucentFacingsMixin {
	@WrapOperation(method = "sortQuads", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/MeshData$SortState;buildSortedIndexBuffer(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder;Lcom/mojang/blaze3d/vertex/VertexSorting;)Lcom/mojang/blaze3d/vertex/ByteBufferBuilder$Result;"))
	private ByteBufferBuilder.Result afterburner$facings(MeshData.SortState state, ByteBufferBuilder target, VertexSorting sorting,
			Operation<ByteBufferBuilder.Result> original) {
		if (sorting instanceof TranslucentCulling.RelativeSorting) {
			MeshData mesh = (MeshData) (Object) this;
			MeshData.DrawState draw = mesh.drawState();
			byte[] facings = TranslucentCulling.facings(mesh.vertexBuffer(), draw.vertexCount(), draw.format());
			if (facings != null) ((TranslucentCulling.Facings) (Object) state).afterburner$setFacings(facings);
		}
		return original.call(state, target, sorting);
	}
}
