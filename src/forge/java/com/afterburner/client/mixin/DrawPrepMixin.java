package com.afterburner.client.mixin;

import com.afterburner.client.render.RegionMesh;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Every frame, vanilla looks up the GPU buffer of all three layers of every visible section, with two hash map
 * lookups each, before checking if the layer has anything to draw. Most sections only have one or two layers.
 */
@Mixin(LevelRenderer.class)
public class DrawPrepMixin {
	@WrapOperation(method = "extractSectionDrawGroups", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;getRenderSectionSlice(Lnet/minecraft/client/renderer/chunk/SectionMesh;Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSectionBufferSlice;"))
	private SectionRenderDispatcher.RenderSectionBufferSlice afterburner$skipEmptyLayers(SectionRenderDispatcher dispatcher, SectionMesh mesh,
			ChunkSectionLayer layer, Operation<SectionRenderDispatcher.RenderSectionBufferSlice> original) {
		// Vanilla skips the layer anyway when it has no draw, so not looking up its buffer changes nothing.
		if (mesh.getSectionDraw(layer) == null) return null;
		return mesh instanceof RegionMesh regional ? regional.afterburner$slice(dispatcher, layer) : original.call(dispatcher, mesh, layer);
	}
}
