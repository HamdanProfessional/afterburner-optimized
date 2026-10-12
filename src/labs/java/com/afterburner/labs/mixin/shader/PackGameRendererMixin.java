package com.afterburner.labs.mixin.shader;

import com.afterburner.labs.shaderpack.game.Shaderpacks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Around the world with a pack: the view bobbing in the view rather than the projection, the hand drawn into the pack's
 * buffers without the game's depth clear, and composite and final after it (before the screen's overlays: fire, water,
 * a block the head is in), or after the world without a hand.
 */
@Mixin(GameRenderer.class)
public abstract class PackGameRendererMixin {
	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", ordinal = 0, target = "Lorg/joml/Matrix4f;mul(Lorg/joml/Matrix4fc;)Lorg/joml/Matrix4f;"))
	private Matrix4f afterburner$bobOutOfProjection(Matrix4f projection, Matrix4fc bob, Operation<Matrix4f> original) {
		return Shaderpacks.movesBob(Minecraft.getInstance()) ? projection : original.call(projection, bob);
	}

	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;rotation(Lorg/joml/Quaternionfc;)Lorg/joml/Matrix4f;"))
	private Matrix4f afterburner$bobIntoView(Matrix4f view, Quaternionfc rotation, Operation<Matrix4f> original, @Local PoseStack bob) {
		Matrix4f m = original.call(view, rotation);
		if (Shaderpacks.movesBob(Minecraft.getInstance())) bob.last().pose().mul(m, m);
		return m;
	}

	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"))
	private void afterburner$handDepth(int mask, boolean checkError, Operation<Void> original) {
		if (!Shaderpacks.beforeHand()) original.call(mask, checkError);
	}

	@Inject(method = "renderItemInHand", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;renderScreenEffect(Lnet/minecraft/client/Minecraft;Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
	private void afterburner$finishBeforeOverlays(Camera camera, float partialTick, Matrix4f view, CallbackInfo ci) {
		Shaderpacks.finish(Minecraft.getInstance());
	}

	@Inject(method = "renderLevel", at = @At("TAIL"))
	private void afterburner$finish(DeltaTracker delta, CallbackInfo ci) {
		Shaderpacks.finish(Minecraft.getInstance());
	}
}
