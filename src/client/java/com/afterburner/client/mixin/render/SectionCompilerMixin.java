package com.afterburner.client.mixin.render;

import com.afterburner.client.render.ChunkRegions;
import com.afterburner.client.render.RegionResults;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stores every freshly built section's vertices relative to its region (see {@link ChunkRegions}). */
@Mixin(SectionCompiler.class)
public class SectionCompilerMixin {
	@Inject(method = "compile", at = @At("RETURN"))
	private void afterburner$moveToRegion(SectionPos sectionPos, RenderSectionRegion region, VertexSorting sorting, SectionBufferBuilderPack builders,
			CallbackInfoReturnable<SectionCompiler.Results> cir) {
		SectionCompiler.Results results = cir.getReturnValue();
		((RegionResults) (Object) results).afterburner$setRegionSlot(ChunkRegions.moveToRegion(sectionPos, results.renderedLayers) ? ChunkRegions.slot(sectionPos) : -1);
		((RegionResults) (Object) results).afterburner$setRegionKey(ChunkRegions.key(sectionPos.minBlockX(), sectionPos.minBlockY(), sectionPos.minBlockZ()));
	}
}
