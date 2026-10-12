package com.afterburner.bench.mixin.client;

import com.afterburner.bench.client.BuildTimes;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;

/** Times section builds, for {@link BuildTimes}. */
@Mixin(SectionCompiler.class)
public class BuildTimeMixin {
	@WrapMethod(method = "compile")
	private SectionCompiler.Results afterburner$time(SectionPos sectionPos, RenderSectionRegion region, VertexSorting sorting,
			SectionBufferBuilderPack builders, Operation<SectionCompiler.Results> original) {
		long start = System.nanoTime();
		try {
			return original.call(sectionPos, region, sorting, builders);
		} finally {
			BuildTimes.built(System.nanoTime() - start);
		}
	}
}
