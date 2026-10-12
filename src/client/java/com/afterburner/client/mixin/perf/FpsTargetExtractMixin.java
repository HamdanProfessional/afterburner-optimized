package com.afterburner.client.mixin.perf;

import com.afterburner.client.perf.FpsTarget;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
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
@Mixin(LevelRenderer.class)
public class FpsTargetExtractMixin {
	@Shadow
	@Final
	private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

	@ModifyExpressionValue(method = "setupRender", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;consumeFrustumUpdate()Z"))
	private boolean afterburner$drawDistanceChanged(boolean update) {
		return FpsTarget.consumeDrawChange() | update;
	}

	@Inject(method = "applyFrustum", at = @At("TAIL"))
	private void afterburner$drawDistance(CallbackInfo ci) {
		if (FpsTarget.drawCap() == FpsTarget.NONE) return;
		Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
		FpsTarget.filter(visibleSections, camera.x, camera.z);
	}

	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"))
	private boolean afterburner$entityDrawDistance(EntityRenderDispatcher dispatcher, Entity entity, Frustum frustum, double x, double y, double z,
			Operation<Boolean> original) {
		if (!original.call(dispatcher, entity, frustum, x, y, z)) return false;
		return FpsTarget.drawCap() == FpsTarget.NONE
				|| FpsTarget.drawn(Mth.floor(x) >> 4, Mth.floor(z) >> 4, entity.getBlockX() >> 4, entity.getBlockZ() >> 4);
	}
}
