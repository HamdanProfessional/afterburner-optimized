package com.afterburner.labs.mixin.shader;

import com.afterburner.labs.shaderpack.game.ShadowEntities;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Which entities the game draws for the camera, and then those that only cast a shader pack's shadow (ShadowEntities). */
@Mixin(LevelExtractor.class)
public class PackLevelExtractorMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Shadow
	private @Nullable ClientLevel level;

	@Inject(method = "extractVisibleEntities", at = @At("HEAD"))
	private void afterburner$beginEntities(Camera camera, Frustum frustum, DeltaTracker deltaTracker, LevelRenderState output, CallbackInfo ci) {
		ShadowEntities.begin();
	}

	@WrapOperation(method = "extractVisibleEntities", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
	private EntityRenderState afterburner$forCamera(LevelExtractor self, Entity entity, float partialTicks, Operation<EntityRenderState> original) {
		ShadowEntities.forCamera(entity);
		return original.call(self, entity, partialTicks);
	}

	@Inject(method = "extractVisibleEntities", at = @At("TAIL"))
	private void afterburner$shadowEntities(Camera camera, Frustum frustum, DeltaTracker deltaTracker, LevelRenderState output, CallbackInfo ci) {
		ShadowEntities.extract(this.minecraft, this.level, camera, deltaTracker);
	}
}
