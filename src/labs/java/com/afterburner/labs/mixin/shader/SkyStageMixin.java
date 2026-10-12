package com.afterburner.labs.mixin.shader;

import com.afterburner.labs.shaderpack.game.Shaderpacks;
import com.afterburner.labs.shaderpack.game.Shaderpacks.SkyStage;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * What the sky draws where its shader doesn't tell (the sky, the stars and the dark disc under the horizon share one; the
 * sun and the moon another), for the pack's programs and render stages.
 */
@Mixin(LevelRenderer.class)
public abstract class SkyStageMixin {
	private static final String BUFFER_DRAW = "Lcom/mojang/blaze3d/vertex/VertexBuffer;drawWithShader(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/ShaderInstance;)V";
	private static final String MESH_DRAW = "Lcom/mojang/blaze3d/vertex/BufferUploader;drawWithShader(Lcom/mojang/blaze3d/vertex/MeshData;)V";

	private static void afterburner$buffer(SkyStage stage, VertexBuffer buffer, Matrix4f view, Matrix4f projection, ShaderInstance shader,
			Operation<Void> original) {
		Shaderpacks.skyStage(stage);
		try {
			original.call(buffer, view, projection, shader);
		} finally {
			Shaderpacks.skyStage(SkyStage.NONE);
		}
	}

	private static void afterburner$mesh(SkyStage stage, MeshData mesh, Operation<Void> original) {
		Shaderpacks.skyStage(stage);
		try {
			original.call(mesh);
		} finally {
			Shaderpacks.skyStage(SkyStage.NONE);
		}
	}

	@WrapOperation(method = "renderSky", at = @At(value = "INVOKE", target = BUFFER_DRAW, ordinal = 0))
	private void afterburner$sky(VertexBuffer buffer, Matrix4f view, Matrix4f projection, ShaderInstance shader, Operation<Void> original) {
		afterburner$buffer(SkyStage.SKY, buffer, view, projection, shader, original);
	}

	@WrapOperation(method = "renderSky", at = @At(value = "INVOKE", target = BUFFER_DRAW, ordinal = 1))
	private void afterburner$stars(VertexBuffer buffer, Matrix4f view, Matrix4f projection, ShaderInstance shader, Operation<Void> original) {
		afterburner$buffer(SkyStage.STARS, buffer, view, projection, shader, original);
	}

	@WrapOperation(method = "renderSky", at = @At(value = "INVOKE", target = BUFFER_DRAW, ordinal = 2))
	private void afterburner$void(VertexBuffer buffer, Matrix4f view, Matrix4f projection, ShaderInstance shader, Operation<Void> original) {
		afterburner$buffer(SkyStage.VOID, buffer, view, projection, shader, original);
	}

	@WrapOperation(method = "renderSky", at = @At(value = "INVOKE", target = MESH_DRAW, ordinal = 0))
	private void afterburner$sunset(MeshData mesh, Operation<Void> original) {
		afterburner$mesh(SkyStage.SUNSET, mesh, original);
	}

	@WrapOperation(method = "renderSky", at = @At(value = "INVOKE", target = MESH_DRAW, ordinal = 1))
	private void afterburner$sun(MeshData mesh, Operation<Void> original) {
		afterburner$mesh(SkyStage.SUN, mesh, original);
	}

	@WrapOperation(method = "renderSky", at = @At(value = "INVOKE", target = MESH_DRAW, ordinal = 2))
	private void afterburner$moon(MeshData mesh, Operation<Void> original) {
		afterburner$mesh(SkyStage.MOON, mesh, original);
	}

	@WrapOperation(method = "renderSky", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderEndSky(Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
	private void afterburner$endSky(LevelRenderer self, PoseStack pose, Operation<Void> original) {
		Shaderpacks.skyStage(SkyStage.CUSTOM);
		try {
			original.call(self, pose);
		} finally {
			Shaderpacks.skyStage(SkyStage.NONE);
		}
	}
}
