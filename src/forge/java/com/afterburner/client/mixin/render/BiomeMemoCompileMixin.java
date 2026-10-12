package com.afterburner.client.mixin.render;

import com.afterburner.client.render.BiomeMemo;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;

/** Biomes are remembered for the length of one section build, see {@link BiomeMemo}. */
@Mixin(SectionCompiler.class)
public class BiomeMemoCompileMixin {
	@WrapMethod(method = "compile")
	private SectionCompiler.Results afterburner$rememberBiomes(SectionPos sectionPos, RenderSectionRegion region, VertexSorting sorting,
			SectionBufferBuilderPack builders, Operation<SectionCompiler.Results> original) {
		BiomeMemo.begin(sectionPos);
		try {
			return original.call(sectionPos, region, sorting, builders);
		} finally {
			BiomeMemo.end();
		}
	}
}
