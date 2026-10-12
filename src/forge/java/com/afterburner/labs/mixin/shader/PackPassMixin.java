package com.afterburner.labs.mixin.shader;

import com.afterburner.labs.shaderpack.game.Shaderpacks;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** With a shader pack on, the game's world passes draw into the pack's buffers (see {@link Shaderpacks#redirect}). */
@Mixin(FrontendCommandEncoder.class)
public class PackPassMixin {
	@ModifyVariable(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;",
			at = @At("HEAD"), argsOnly = true)
	private RenderPassDescriptor afterburner$packTargets(RenderPassDescriptor descriptor) {
		return Shaderpacks.active() ? Shaderpacks.redirect(descriptor) : descriptor;
	}

	@Inject(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;",
			at = @At("RETURN"))
	private void afterburner$packPass(RenderPassDescriptor descriptor, CallbackInfoReturnable<RenderPass> cir) {
		if (Shaderpacks.active()) Shaderpacks.opened(cir.getReturnValue());
	}
}
