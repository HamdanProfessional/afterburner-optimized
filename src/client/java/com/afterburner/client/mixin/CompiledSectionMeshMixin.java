package com.afterburner.client.mixin;

import com.afterburner.client.render.FaceSortedMesh;
import com.afterburner.client.render.SectionFaces;
import com.afterburner.client.render.TerrainExtras;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** Groups each opaque layer's quads by facing right after a section is built, before it's uploaded. */
@Mixin(CompiledSectionMesh.class)
public class CompiledSectionMeshMixin implements FaceSortedMesh {
	@Unique
	private final SectionFaces[] afterburner$faces = new SectionFaces[ChunkSectionLayer.values().length];

	@Inject(method = "<init>", at = @At("RETURN"))
	private void afterburner$sortFaces(TranslucencyPointOfView pointOfView, SectionCompiler.Results results, long startTime, CallbackInfo ci) {
		TranslucencyPointOfViewAccessor camera = (TranslucencyPointOfViewAccessor) (Object) pointOfView;
		int @Nullable [] @Nullable [] runs = ((TerrainExtras.Holder) (Object) results).afterburner$blockRuns();
		for (Map.Entry<ChunkSectionLayer, MeshData> entry : results.renderedLayers.entrySet()) {
			ChunkSectionLayer layer = entry.getKey();
			MeshData mesh = entry.getValue();
			MeshData.DrawState state = mesh.drawState();
			// Translucent layers are sorted back to front with their own index buffer, so they stay as they are.
			if (layer.translucent() || mesh.indexBuffer() != null || state.primitiveTopology() != PrimitiveTopology.QUADS
					|| state.format() != DefaultVertexFormat.BLOCK) continue;
			int @Nullable [] layerRuns = runs != null ? runs[layer.ordinal()] : null;
			int @Nullable [] moved = layerRuns != null ? new int[state.vertexCount() / 4] : null;
			SectionFaces faces = SectionFaces.sort(mesh.vertexBuffer(), state.vertexCount(), state.format().getVertexSize(),
					camera.afterburner$x(), camera.afterburner$y(), camera.afterburner$z(), moved);
			afterburner$faces[layer.ordinal()] = faces;
			// The blocks the quads came from (for a shader pack's IDs) go where the quads went.
			if (faces != null && layerRuns != null && moved != null) runs[layer.ordinal()] = TerrainExtras.moveRuns(layerRuns, moved);
		}
	}

	@Override
	public @Nullable SectionFaces afterburner$faces(ChunkSectionLayer layer) {
		return afterburner$faces[layer.ordinal()];
	}
}
