package com.afterburner.client.mixin.render;

import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The view area's sections, for {@link com.afterburner.client.render.OcclusionRays#inView}. */
@Mixin(ViewArea.class)
public interface ViewAreaAccessor {
	@Accessor("sections")
	RotatingSectionStorage<SectionRenderDispatcher.RenderSection> afterburner$sections();
}
