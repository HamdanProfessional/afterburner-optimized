package com.afterburner.client.mixin.render;

import com.afterburner.client.render.LightMemo;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tells this thread's light cache which section is being built, see {@link LightMemo}. */
@Mixin(SectionCompiler.class)
public class LightMemoCompileMixin {
	@Inject(method = "compile", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/BlockModelLighter;enableCaching()V", shift = At.Shift.AFTER))
	private void afterburner$anchorLight(SectionPos sectionPos, RenderSectionRegion region, VertexSorting sorting, SectionBufferBuilderPack builders,
			CallbackInfoReturnable<SectionCompiler.Results> cir) {
		((LightMemo) BlockModelLighterAccessor.afterburner$cache().get()).afterburner$anchor(sectionPos);
	}
}
