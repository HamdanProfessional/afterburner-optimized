package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedMesh;
import com.afterburner.client.render.AnimatedSprites;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Finds the animated textures a freshly built section shows, before its meshes go to the GPU (see {@link AnimatedSprites}). */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$RebuildTask")
public class AnimatedScanMixin {
	@Inject(method = "doTask", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
			target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$CompiledSection;transparencyState:Lcom/mojang/blaze3d/vertex/MeshData$SortState;"))
	private void afterburner$findAnimated(SectionBufferBuilderPack builders, CallbackInfoReturnable<?> cir, @Local SectionCompiler.Results results,
			@Local SectionRenderDispatcher.CompiledSection compiled) {
		((AnimatedMesh) compiled).afterburner$setAnimated(AnimatedSprites.scan(results.renderedLayers.values()));
	}
}
