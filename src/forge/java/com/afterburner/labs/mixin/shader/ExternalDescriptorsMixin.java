package com.afterburner.labs.mixin.shader;

import com.afterburner.labs.shaderpack.game.ExternalBindings;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A pack's images and storage buffers are bound by us, not by the pipeline (see {@link ExternalBindings}). */
@Mixin(PipelineBuilder.class)
public class ExternalDescriptorsMixin {
	@WrapOperation(method = "generateBackendCreateInfo", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/api/SpvModule$Reflection;descriptors()Ljava/util/List;"))
	private List<SpvModule.Reflection.Descriptor> afterburner$external(SpvModule.Reflection reflection,
			Operation<List<SpvModule.Reflection.Descriptor>> original, @Local(argsOnly = true) RenderPipeline pipeline) {
		return ExternalBindings.descriptors(pipeline, original.call(reflection));
	}
}
