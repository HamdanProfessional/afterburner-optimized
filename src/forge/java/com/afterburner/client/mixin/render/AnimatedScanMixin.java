package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedMesh;
import com.afterburner.client.render.AnimatedSprites;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Finds the animated textures a freshly built section shows (see {@link AnimatedSprites}). */
@Mixin(SectionCompiler.class)
public class AnimatedScanMixin {
	@Inject(method = "compile", at = @At("RETURN"))
	private void afterburner$findAnimated(SectionPos sectionPos, RenderSectionRegion region, VertexSorting sorting, SectionBufferBuilderPack builders,
			CallbackInfoReturnable<SectionCompiler.Results> cir) {
		SectionCompiler.Results results = cir.getReturnValue();
		((AnimatedMesh) (Object) results).afterburner$setAnimated(AnimatedSprites.scan(results.renderedLayers.values()));
	}
}
