package com.afterburner.labs.mixin.shader;

import com.afterburner.labs.shaderpack.game.Shaderpacks;
import com.mojang.blaze3d.ProjectionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * What the game draws on top of something else at the same place (entity shadows on the ground, armor trims, block outlines)
 * is moved toward the camera by a 4096th of its distance (the render types' layering). The game's depth is reversed, which
 * that is plenty for; a pack's is the old kind, much coarser far off, where it flickered against what it lies on (packs jitter
 * the view every frame for their anti-aliasing). With a pack on it's moved eight times as far: still a fraction of a pixel.
 */
@Mixin(ProjectionType.class)
public class PackLayeringMixin {
	@Unique
	private static final float AFTERBURNER$PACK_LAYERING = 8.0F;

	@ModifyVariable(method = "applyLayeringTransform", at = @At("HEAD"), argsOnly = true)
	private float afterburner$packLayering(float bias) {
		return Shaderpacks.active() ? bias * AFTERBURNER$PACK_LAYERING : bias;
	}
}
