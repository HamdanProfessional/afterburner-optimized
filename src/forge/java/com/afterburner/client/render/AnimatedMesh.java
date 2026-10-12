package com.afterburner.client.render;

import net.minecraft.client.renderer.texture.SpriteContents;
import org.jspecify.annotations.Nullable;

/** Added to a section's build results and mesh: the animated textures it shows ({@link AnimatedSprites#scan}). */
public interface AnimatedMesh {
	SpriteContents @Nullable [] afterburner$animated();

	void afterburner$setAnimated(SpriteContents @Nullable [] sprites);
}
