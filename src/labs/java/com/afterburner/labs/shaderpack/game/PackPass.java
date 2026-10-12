package com.afterburner.labs.shaderpack.game;

/** What a shader pack draws into while the game draws: the kinds of its world passes. */
public final class PackPass {
	public enum Kind {
		/** The sky: gbuffers_skybasic, gbuffers_skytextured. */
		SKY,
		/** The world: terrain, entities, particles, ... */
		WORLD,
		/**
		 * The world after the deferred passes (translucents), when the pack's world buffers are too many for one framebuffer
		 * (PackLoader.Loaded#splitsWorld): on buffers of its own. Otherwise it stays WORLD.
		 */
		TRANSLUCENT,
		/** The hand: gbuffers_hand. */
		HAND,
		/** Our shadow map ({@link ShadowMap}): shadow, shadow_solid, shadow_cutout, shadow_water, shadow_entities. */
		SHADOW,
		/** The shadow map's entities drawn again into shadowtex1 ({@link ShadowMap#renderEntities}): SHADOW's programs, depth alone. */
		SHADOW_DEPTH;

		/** Whether it draws the shadow map. */
		boolean shadow() {
			return this == SHADOW || this == SHADOW_DEPTH;
		}
	}

	private PackPass() {
	}
}
