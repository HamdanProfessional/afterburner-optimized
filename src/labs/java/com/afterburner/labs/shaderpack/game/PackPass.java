package com.afterburner.labs.shaderpack.game;

import org.jspecify.annotations.Nullable;

/**
 * A game render pass the shader pack draws in instead (implemented on the game's passes by PackPipelineMixin): its draws go to
 * the pack's buffers with the pack's programs.
 */
public interface PackPass {
	enum Kind {
		/** The "Sky" pass: gbuffers_skybasic, gbuffers_skytextured. */
		SKY,
		/** The "Main" pass: terrain, entities, particles, ... */
		WORLD,
		/**
		 * The "Main" pass after the deferred passes (translucents), when the pack's world buffers are too many for one pass
		 * (PackLoader.Loaded#splitsWorld): on buffers of its own. Otherwise it stays WORLD.
		 */
		TRANSLUCENT,
		/** The "Item in hand" pass: gbuffers_hand. */
		HAND,
		/** Our shadow map pass ({@link ShadowMap}): shadow, shadow_solid, shadow_cutout, shadow_water, shadow_entities. */
		SHADOW,
		/** The shadow map's entities drawn again into shadowtex1 ({@link ShadowMap#renderEntities}): SHADOW's programs, depth alone. */
		SHADOW_DEPTH;

		/** Whether the pass draws the shadow map. */
		boolean shadow() {
			return this == SHADOW || this == SHADOW_DEPTH;
		}
	}

	PackPass.@Nullable Kind afterburner$packKind();

	void afterburner$setPackKind(PackPass.@Nullable Kind kind);
}
