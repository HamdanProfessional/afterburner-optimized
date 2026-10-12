package com.afterburner.client.mixin.render;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The sections of the graph vanilla draws from ({@link FrustumPassMixin}). */
@Mixin(targets = "net.minecraft.client.renderer.SectionOcclusionGraph$GraphState")
public interface GraphStateAccessor {
	@Accessor("storage")
	SectionOcclusionGraph.GraphStorage afterburner$storage();
}
