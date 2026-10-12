package com.afterburner.client.mixin.particle;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The sections the visibility update passes through without listing (only air) and the chunks it waits for, for {@link com.afterburner.client.render.ParticleCulling}. */
@Mixin(SectionOcclusionGraph.class)
public interface OcclusionGraphAccessor {
	@Accessor("emptySections")
	LongOpenHashSet afterburner$emptySections();

	@Accessor("loadedChunks")
	LongOpenHashSet afterburner$loadedChunks();
}
