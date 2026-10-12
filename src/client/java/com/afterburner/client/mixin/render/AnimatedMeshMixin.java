package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedMesh;
import com.afterburner.client.render.AnimatedSprites;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** A built section remembers the animated textures it shows (see {@link AnimatedSprites}). */
@Mixin(SectionRenderDispatcher.CompiledSection.class)
public class AnimatedMeshMixin implements AnimatedMesh {
	@Unique
	private SpriteContents @Nullable [] afterburner$animated;

	@Override
	public SpriteContents @Nullable [] afterburner$animated() {
		return afterburner$animated;
	}

	@Override
	public void afterburner$setAnimated(SpriteContents @Nullable [] sprites) {
		afterburner$animated = sprites;
	}
}
