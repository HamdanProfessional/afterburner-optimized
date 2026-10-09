package com.afterburner.client.mixin.render;

import com.afterburner.client.render.BlockEntitySections;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The block entities of visible sections are looked for only in the sections that have some ({@link BlockEntitySections}). */
@Mixin(LevelExtractor.class)
public class BlockEntityScanMixin {
	@WrapOperation(method = "extractVisibleBlockEntities", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections()Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
	private ObjectArrayList<SectionRenderDispatcher.RenderSection> afterburner$withBlockEntities(LevelRenderer renderer,
			Operation<ObjectArrayList<SectionRenderDispatcher.RenderSection>> original) {
		return BlockEntitySections.of(original.call(renderer));
	}
}
