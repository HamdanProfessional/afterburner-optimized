package com.afterburner.client.mixin.render;

import com.afterburner.client.render.RestartablePass;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every render pass remembers what it was made with, so it can be restarted (see {@link RestartablePass}). */
@Mixin(FrontendCommandEncoder.class)
public class PassDescriptorMixin {
	@Inject(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;", at = @At("RETURN"))
	private void afterburner$remember(RenderPassDescriptor descriptor, CallbackInfoReturnable<RenderPass> cir) {
		if (cir.getReturnValue() instanceof RestartablePass pass) pass.afterburner$setDescriptor(descriptor);
	}
}
