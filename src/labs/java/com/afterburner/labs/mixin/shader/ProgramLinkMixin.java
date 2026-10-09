package com.afterburner.labs.mixin.shader;

import com.afterburner.labs.shaderpack.game.ProgramLinker;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.opengl.GlDebugLabel;
import com.mojang.renderpearl.backend.opengl.GlPipelineRecompiler;
import com.mojang.renderpearl.backend.opengl.GlProgram;
import java.util.Map;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The shader pack's programs are linked by {@link ProgramLinker}'s thread: handed over once the game has their GLSL (on a
 * background thread), and picked up, linked, where the game would link them on the render thread.
 */
@Mixin(GlPipelineRecompiler.class)
public class ProgramLinkMixin {
	@Shadow
	@Final
	private GlDebugLabel debugLabels;

	@Inject(method = "decompileShaders", at = @At("RETURN"))
	private void afterburner$linkElsewhere(BackendRenderPipeline.CreateInfo info,
			CallbackInfoReturnable<Map<BackendRenderPipeline.CreateInfo.Shader, String>> cir) {
		ProgramLinker.submit(info, cir.getReturnValue());
	}

	@Inject(method = "compileProgram", at = @At("HEAD"), cancellable = true)
	private void afterburner$linked(BackendRenderPipeline.CreateInfo info, Map<BackendRenderPipeline.CreateInfo.Shader, String> decompiled,
			CallbackInfoReturnable<GlProgram> cir) {
		Integer program = ProgramLinker.take(info.name());
		if (program == null) return;
		if (program == 0) {
			cir.setReturnValue(null);
			return;
		}
		GlProgram linked = GlProgramAccessor.afterburner$create(program, info.name());
		linked.setupBindGroupLayouts(info.uniforms());
		this.debugLabels.applyLabel(linked);
		cir.setReturnValue(linked);
	}
}
