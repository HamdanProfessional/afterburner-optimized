package com.afterburner.labs.mixin.shader;

import com.afterburner.labs.shaderpack.game.Shaderpacks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The world's drawing with a pack: the pack's frame starts in place of the game's clear, the profiler's sections tell it
 * which part of the frame is drawn, and Fabulous graphics' own buffers are left out (the pack draws everything into its own).
 */
@Mixin(LevelRenderer.class)
public abstract class PackLevelRendererMixin {
	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"))
	private void afterburner$beginPack(int mask, boolean checkError, Operation<Void> original, @Local(argsOnly = true) DeltaTracker delta,
			@Local(argsOnly = true, ordinal = 0) Matrix4f view, @Local(argsOnly = true, ordinal = 1) Matrix4f projection) {
		if (!Shaderpacks.beginLevel(Minecraft.getInstance(), delta, view, projection)) original.call(mask, checkError);
	}

	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V"))
	private void afterburner$section(ProfilerFiller profiler, String name, Operation<Void> original) {
		original.call(profiler, name);
		Shaderpacks.section(name);
	}

	@ModifyExpressionValue(method = "renderLevel", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
			target = "Lnet/minecraft/client/renderer/LevelRenderer;transparencyChain:Lnet/minecraft/client/renderer/PostChain;"))
	private @Nullable PostChain afterburner$noFabulous(@Nullable PostChain chain) {
		return Shaderpacks.hidesFabulous() ? null : chain;
	}

	@ModifyExpressionValue(method = "renderLevel", at = {
		@At(value = "FIELD", opcode = Opcodes.GETFIELD, target = "Lnet/minecraft/client/renderer/LevelRenderer;translucentTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;"),
		@At(value = "FIELD", opcode = Opcodes.GETFIELD, target = "Lnet/minecraft/client/renderer/LevelRenderer;itemEntityTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;"),
		@At(value = "FIELD", opcode = Opcodes.GETFIELD, target = "Lnet/minecraft/client/renderer/LevelRenderer;weatherTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;")
	})
	private @Nullable RenderTarget afterburner$noFabulousTarget(@Nullable RenderTarget target) {
		return Shaderpacks.hidesFabulous() ? null : target;
	}
}
