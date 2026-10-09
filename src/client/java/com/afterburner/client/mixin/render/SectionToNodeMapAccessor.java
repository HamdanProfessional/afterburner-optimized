package com.afterburner.client.mixin.render;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The occlusion graph's nodes by section index, for {@link com.afterburner.client.render.OcclusionRays#inView}. */
@Mixin(SectionOcclusionGraph.SectionToNodeMap.class)
public interface SectionToNodeMapAccessor {
	@Accessor("nodes")
	SectionOcclusionGraph.Node[] afterburner$nodes();
}
