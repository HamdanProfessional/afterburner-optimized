package com.afterburner.client.render;

/** Added to every texture's contents: when it was last on screen ({@link AnimatedSprites}). Render thread only. */
public interface SeenSprite {
	int afterburner$seenAt();

	void afterburner$see(int tick);

	/** The last list of textures on screen this one was put in, so it goes in once. */
	int afterburner$listed();

	void afterburner$list(int stamp);
}
