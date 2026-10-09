package com.afterburner.client.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A section keeps its last build task (to cancel it), and the task kept the copy of the 27 sections around it that it
 * was built from: hundreds of MB of block data at high render distances. The copy is let go once the build has it.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public class BuildCopyMixin {
	@Shadow
	@Final
	@Mutable
	private RenderSectionRegion region;

	@WrapOperation(method = "doTask", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/SectionCompiler;compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;"))
	private SectionCompiler.Results afterburner$letGo(SectionCompiler compiler, SectionPos pos, RenderSectionRegion copy, VertexSorting sorting,
			SectionBufferBuilderPack buffers, Operation<SectionCompiler.Results> original) {
		try {
			return original.call(compiler, pos, copy, sorting, buffers);
		} finally {
			region = null;
		}
	}
}
