package com.afterburner.labs.mixin.lod;

import com.afterburner.labs.lod.Lod;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Far terrain: picked and uploaded before the solid terrain, drawn right after the cutout terrain (the opaque terrain is
 * all in the depth buffer then, and the occlusion test has seen only the game's own). With a shader pack that draws it,
 * its dh_terrain draws it instead, right after the picking: the game's terrain goes over it (see Lod#prepare).
 */
@Mixin(LevelRenderer.class)
public abstract class LodLevelRendererMixin {
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", ordinal = 0,
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
	private void afterburner$prepareFarTerrain(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
			LightTexture lightTexture, Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
		Lod.prepare((LevelRenderer) (Object) this, camera, modelView, projection);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", ordinal = 2, shift = At.Shift.AFTER,
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
	private void afterburner$drawFarTerrain(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
			LightTexture lightTexture, Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
		Lod.draw();
	}
}
