package com.afterburner.client.mixin.render;

import com.afterburner.client.render.TranslucentCulling;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.ByteBuffer;

/** Notes which way each translucent quad faces while vanilla reads the quad centers it sorts by (see {@link TranslucentCulling}). */
@Mixin(MeshData.class)
public class TranslucentFacingsMixin {
	@WrapOperation(method = "sortQuads", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/MeshData;decodeQuadCentroids(Ljava/nio/ByteBuffer;ILcom/mojang/renderpearl/api/vertex/VertexFormat;Lcom/mojang/blaze3d/vertex/CompactVectorArray;I)V"))
	private void afterburner$facings(ByteBuffer vertices, int vertexCount, VertexFormat format, CompactVectorArray output, int outputIndex, Operation<Void> original) {
		original.call(vertices, vertexCount, format, output, outputIndex);
		byte[] facings = outputIndex == 0 ? TranslucentCulling.facings(vertices, vertexCount, format) : null;
		if (facings != null) ((TranslucentCulling.Facings) output).afterburner$setFacings(facings);
	}
}
