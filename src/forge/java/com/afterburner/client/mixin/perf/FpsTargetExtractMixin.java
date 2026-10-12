package com.afterburner.client.mixin.perf;

import com.afterburner.client.perf.FpsTarget;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Leaves out the sections, block entities and entities past the chunk distance {@link FpsTarget} draws to. */
@Mixin(LevelExtractor.class)
public class FpsTargetExtractMixin {
	@Shadow
	@Final
	private LevelRenderer levelRenderer;

	@ModifyExpressionValue(method = "extract", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;consumeFrustumUpdate()Z"))
	private boolean afterburner$drawDistanceChanged(boolean update) {
		return FpsTarget.consumeDrawChange() | update;
	}

	@Inject(method = "applyFrustum", at = @At("TAIL"))
	private void afterburner$drawDistance(CallbackInfo ci) {
		if (FpsTarget.drawCap() == FpsTarget.NONE) return;
		Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		FpsTarget.filter(levelRenderer.visibleSections(), camera.x, camera.z);
		FpsTarget.filter(levelRenderer.nearbyVisibleSections(), camera.x, camera.z);
	}

	@ModifyReturnValue(method = "isEntityVisible", at = @At("RETURN"))
	private boolean afterburner$entityDrawDistance(boolean visible, @Local(argsOnly = true) Entity entity) {
		if (!visible || FpsTarget.drawCap() == FpsTarget.NONE) return visible;
		Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		return FpsTarget.drawn(Mth.floor(camera.x) >> 4, Mth.floor(camera.z) >> 4, entity.getBlockX() >> 4, entity.getBlockZ() >> 4);
	}
}
