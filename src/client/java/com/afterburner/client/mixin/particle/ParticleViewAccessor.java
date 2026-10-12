package com.afterburner.client.mixin.particle;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** What the level renderer finds in view this frame, for culling particles. */
@Mixin(LevelRenderer.class)
public interface ParticleViewAccessor {
	@Accessor("viewArea")
	@Nullable ViewArea afterburner$viewArea();

	@Accessor("visibleSections")
	ObjectArrayList<SectionRenderDispatcher.RenderSection> afterburner$visibleSections();

	@Accessor("cullingFrustum")
	Frustum afterburner$cullingFrustum();

	@Accessor("capturedFrustum")
	@Nullable Frustum afterburner$capturedFrustum();
}
