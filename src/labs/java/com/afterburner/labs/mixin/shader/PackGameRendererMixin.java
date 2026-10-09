package com.afterburner.labs.mixin.shader;

import com.afterburner.labs.shaderpack.game.Shaderpacks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Where a shader pack's frame starts (before the world) and ends (composite and final, after the hand). */
@Mixin(GameRenderer.class)
public class PackGameRendererMixin {
	@Shadow
	@Final
	private Minecraft minecraft;
	@Unique
	private final Matrix4f afterburner$view = new Matrix4f();
	@Unique
	private boolean afterburner$bobInView;

	/**
	 * With a pack on, view bobbing (and the hurt tilt) goes in the view matrix instead of the projection, as in Iris: packs take
	 * gbufferProjection for a plain perspective (many project with its diagonal only), so a bob in it moved their reflections
	 * and other screen-space effects as the view bobbed. The world is drawn the same either way.
	 */
	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;mul(Lorg/joml/Matrix4fc;)Lorg/joml/Matrix4f;", ordinal = 0))
	private Matrix4f afterburner$bobInView(Matrix4f projection, Matrix4fc bob, Operation<Matrix4f> original, @Local CameraRenderState cameraState) {
		if (!Shaderpacks.active()) return original.call(projection, bob);
		afterburner$view.set(cameraState.viewRotationMatrix);
		cameraState.viewRotationMatrix.mulLocal(bob);
		afterburner$bobInView = true;
		return projection;
	}

	/** The camera's own rotation again, for what comes after the world (the hand adds its own bob). */
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V",
			shift = At.Shift.AFTER))
	private void afterburner$unbobView(CallbackInfo ci, @Local CameraRenderState cameraState) {
		if (!afterburner$bobInView) return;
		afterburner$bobInView = false;
		cameraState.viewRotationMatrix.set(afterburner$view);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V"))
	private void afterburner$packFrame(CallbackInfo ci, @Local(ordinal = 0) Matrix4f projectionMatrix, @Local CameraRenderState cameraState) {
		Shaderpacks.beginFrame(minecraft, cameraState, projectionMatrix);
	}

	@Inject(method = "render3dHud", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V",
			shift = At.Shift.AFTER))
	private void afterburner$packComposite(CallbackInfo ci) {
		if (Shaderpacks.active()) Shaderpacks.afterHand(minecraft);
	}

	/** Packs do their own translucency: the game's improved transparency stays off while one is on. */
	@Inject(method = "useImprovedTransparency", at = @At("HEAD"), cancellable = true)
	private void afterburner$noImprovedTransparency(CallbackInfoReturnable<Boolean> cir) {
		if (Shaderpacks.active()) cir.setReturnValue(false);
	}
}
