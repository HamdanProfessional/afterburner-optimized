package com.afterburner.labs.mixin.shader;

import com.afterburner.labs.shaderpack.game.ExternalBindings;
import com.mojang.renderpearl.backend.opengl.GlProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sets a program's images, image samplers and storage buffers to our units while it's in use (see {@link ExternalBindings}). */
@Mixin(GlProgram.class)
public class ExternalUnitsMixin {
	@Inject(method = "setupBindGroupLayouts", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_glUseProgram(I)V", ordinal = 1))
	private void afterburner$externalUnits(CallbackInfo ci) {
		ExternalBindings.setup(((GlProgram) (Object) this).getProgramId());
	}
}
