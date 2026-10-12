package com.afterburner.labs.mixin.render;

import com.afterburner.labs.render.WorldScale;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The world drawn smaller and scaled up with FSR 1 before the menus and hotbar (see {@link WorldScale}). */
@Mixin(GameRenderer.class)
public class WorldScaleMixin {
	@WrapOperation(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
	private void afterburner$scaledWorld(GameRenderer self, DeltaTracker delta, Operation<Void> original) {
		boolean scaled = WorldScale.begin(Minecraft.getInstance().getMainRenderTarget());
		try {
			original.call(self, delta);
		} finally {
			if (scaled) WorldScale.end(Minecraft.getInstance().getMainRenderTarget());
		}
	}
}
