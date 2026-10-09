package com.afterburner.client.mixin.render;

import com.afterburner.client.render.TranslucentCulling;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sorts a section's translucent quads like vanilla, with the faces turned away from the camera moved to the end. */
@Mixin(MeshData.SortState.class)
public class TranslucentSortMixin {
	@Inject(method = "buildSortedIndexBuffer", at = @At("HEAD"), cancellable = true)
	private void afterburner$split(ByteBufferBuilder target, VertexSorting sorting, CallbackInfoReturnable<ByteBufferBuilder.Result> cir) {
		MeshData.SortState state = (MeshData.SortState) (Object) this;
		byte[] facings = ((TranslucentCulling.Facings) state.centroids()).afterburner$facings();
		if (TranslucentCulling.ENABLED && sorting instanceof TranslucentCulling.RelativeSorting relative && facings != null
				&& facings.length == state.centroids().size()) {
			cir.setReturnValue(TranslucentCulling.build(state, target, relative, facings));
		} else {
			TranslucentCulling.forget();
		}
	}
}
