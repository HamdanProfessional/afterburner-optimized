package com.afterburner.client.mixin.render;

import com.afterburner.client.render.TranslucentCulling;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Lets a section's translucent sorting know where the camera is relative to the section's corner (see {@link TranslucentCulling}). */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public class TranslucentViewMixin {
	@WrapOperation(method = "createVertexSorting", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/VertexSorting;byDistance(FFF)Lcom/mojang/blaze3d/vertex/VertexSorting;"))
	private VertexSorting afterburner$relative(float x, float y, float z, Operation<VertexSorting> original) {
		return new TranslucentCulling.RelativeSorting(original.call(x, y, z), x, y, z);
	}
}
