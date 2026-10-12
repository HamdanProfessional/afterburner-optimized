package com.afterburner.labs.mixin.render;

import com.afterburner.labs.render.WorldScale;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** The world drawn smaller and scaled up with FSR 1 before the menus and hotbar (see {@link WorldScale}). */
@Mixin(GameRenderer.class)
public class WorldScaleMixin {
	@Shadow
	@Final
	private RenderTarget mainRenderTarget;
	@Shadow
	@Final
	private RenderTarget hud3DTarget;

	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
	private void afterburner$scaledWorld(GameRenderer self, Operation<Void> original) {
		boolean scaled = WorldScale.begin(this.mainRenderTarget, this.hud3DTarget);
		try {
			original.call(self);
		} finally {
			if (scaled) WorldScale.end(this.mainRenderTarget, this.hud3DTarget);
		}
	}
}
