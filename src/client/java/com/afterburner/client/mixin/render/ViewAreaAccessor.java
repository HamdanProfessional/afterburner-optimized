package com.afterburner.client.mixin.render;

import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ViewArea.class)
public interface ViewAreaAccessor {
	@Accessor("sectionGridSizeX")
	int afterburner$sizeX();

	@Accessor("sectionGridSizeY")
	int afterburner$sizeY();

	@Accessor("sectionGridSizeZ")
	int afterburner$sizeZ();

	@Invoker("getRenderSectionAt")
	SectionRenderDispatcher.RenderSection afterburner$sectionAt(BlockPos pos);
}
