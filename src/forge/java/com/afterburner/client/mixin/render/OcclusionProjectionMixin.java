package com.afterburner.client.mixin.render;

import com.afterburner.client.render.OcclusionCulling;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** The occlusion test needs the exact projection the world is drawn with, view bobbing and nausea included. */
@Mixin(GameRenderer.class)
public class OcclusionProjectionMixin {
	@ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private Matrix4f afterburner$levelProjection(Matrix4f projection) {
		OcclusionCulling.projection.set(projection);
		return projection;
	}
}
