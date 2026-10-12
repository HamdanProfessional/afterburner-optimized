package com.afterburner.client.render;

import net.minecraft.client.renderer.texture.SpriteContents;

/** Added to animation states: a block atlas texture's animation waits while it's not on screen ({@link AnimatedSprites}). */
public interface TrackedAnimation {
	void afterburner$track(SpriteContents contents);
}
