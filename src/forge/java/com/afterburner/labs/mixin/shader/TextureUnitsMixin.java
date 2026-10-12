package com.afterburner.labs.mixin.shader;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * The OpenGL backend keeps track of only 12 texture units, so a program with more samplers (shader packs' composite passes
 * often have 15 or more) throws when it binds the 13th. Units go only to samplers a program really uses, and a program can't
 * link with more than the driver's per-stage limit (at least 16, 32 on most), so 64 covers a vertex and a fragment stage.
 */
@Mixin(GlStateManager.class)
public class TextureUnitsMixin {
	private static final int UNITS = 64;

	@ModifyConstant(method = "<clinit>", constant = @Constant(intValue = 12), require = 0)
	private static int afterburner$moreUnits(int units) {
		return Math.max(units, UNITS);
	}
}
