package com.afterburner.labs.mixin.shader;

import com.afterburner.labs.shaderpack.game.Shaderpacks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.PipelineCache;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remembers which pipeline each compiled one is, so a pack can swap in its own version (see {@link Shaderpacks#pipelineFor}). */
@Mixin(PipelineCache.class)
public class PipelineCacheMixin {
	@WrapOperation(method = {"get", "insert"}, at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
	private Object afterburner$remember(Map<Object, Object> cache, Object pipeline, Object compiled, Operation<Object> original) {
		Shaderpacks.remember(pipeline, compiled);
		return original.call(cache, pipeline, compiled);
	}

	@Inject(method = "clear", at = @At("HEAD"))
	private void afterburner$forget(CallbackInfo ci) {
		Shaderpacks.forgetPipelines();
	}
}
