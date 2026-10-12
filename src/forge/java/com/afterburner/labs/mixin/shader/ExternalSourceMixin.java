package com.afterburner.labs.mixin.shader;

import com.afterburner.labs.shaderpack.game.ExternalBindings;
import com.mojang.renderpearl.backend.opengl.GlPipelineRecompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** The GLSL the game hands the driver needs some extensions it doesn't add (see {@link ExternalBindings#source}). */
@Mixin(GlPipelineRecompiler.class)
public class ExternalSourceMixin {
	@ModifyVariable(method = "compileShader", at = @At("HEAD"), argsOnly = true, ordinal = 1)
	private String afterburner$storageExtension(String source) {
		return ExternalBindings.source(source);
	}
}
