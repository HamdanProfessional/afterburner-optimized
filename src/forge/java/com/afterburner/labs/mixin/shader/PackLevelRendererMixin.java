package com.afterburner.labs.mixin.shader;

import com.afterburner.labs.shaderpack.game.ShadowEntities;
import com.afterburner.labs.shaderpack.game.Shaderpacks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A shader pack's entity shadows: the entities cast them without being drawn (ShadowEntities) submitted after the game's, and the
 * shadows drawn once the game prepared its entity draws. The pack's deferred passes between the opaque world and translucents.
 */
@Mixin(LevelRenderer.class)
public class PackLevelRendererMixin {
	@Shadow
	@Final
	private EntityRenderDispatcher entityRenderDispatcher;

	@Inject(method = "submitEntities", at = @At("TAIL"))
	private void afterburner$shadowEntities(PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeCollector output, CallbackInfo ci) {
		ShadowEntities.submit(this.entityRenderDispatcher, poseStack, levelRenderState.cameraRenderState, output);
	}

	@ModifyExpressionValue(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"))
	private FeatureRenderDispatcher.PreparedFrame afterburner$entityShadows(FeatureRenderDispatcher.PreparedFrame features) {
		if (Shaderpacks.active()) Shaderpacks.entityShadows(features);
		return features;
	}

	@Inject(method = "executeClassicTransparency", at = @At("HEAD"))
	private void afterburner$deferred(ChunkSectionsToRender sections, FeatureRenderDispatcher.PreparedFrame featureFrame, RenderPass renderPass,
			CallbackInfo ci) {
		if (Shaderpacks.active()) Shaderpacks.beforeTranslucents(renderPass);
	}
}
