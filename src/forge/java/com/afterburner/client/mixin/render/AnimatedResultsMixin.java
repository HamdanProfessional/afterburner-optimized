package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SectionCompiler.Results.class)
public class AnimatedResultsMixin implements AnimatedMesh {
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
