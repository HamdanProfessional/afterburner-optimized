package com.afterburner.client.mixin.particle;

import com.afterburner.client.render.ParticleCulling;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** The last frame a chunk section was in view, for {@link ParticleCulling#hidden}. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public class SeenSectionMixin implements ParticleCulling.SeenSection {
	@Unique
	private int afterburner$seen = -1;

	@Override
	public int afterburner$seen() {
		return this.afterburner$seen;
	}

	@Override
	public void afterburner$setSeen(int frame) {
		this.afterburner$seen = frame;
	}
}
