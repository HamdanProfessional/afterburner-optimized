package com.afterburner.client.mixin.render;

import com.afterburner.client.render.TerrainExtras;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/** While a shader pack is on, notes which block made which vertices of a section being built (see {@link TerrainExtras}). */
@Mixin(SectionCompiler.class)
public class TerrainBlocksMixin {
	@ModifyExpressionValue(method = "compile", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState afterburner$noteBlock(BlockState state, @Local Map<RenderType, BufferBuilder> layers,
			@Share("afterburner$terrain") LocalRef<TerrainExtras.Recorder> recorder) {
		if (!state.isAir()) {
			TerrainExtras.Recorder r = recorder.get();
			if (r == null) {
				r = TerrainExtras.Recorder.start();
				if (r == null) return state;
				recorder.set(r);
			}
			r.block(layers, state);
		}
		return state;
	}

	@WrapOperation(method = "compile", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderLiquid(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"))
	private void afterburner$noteFluid(BlockRenderDispatcher renderer, BlockPos pos, BlockAndTintGetter level, VertexConsumer output, BlockState state,
			FluidState fluid, Operation<Void> original, @Local Map<RenderType, BufferBuilder> layers,
			@Share("afterburner$terrain") LocalRef<TerrainExtras.Recorder> recorder) {
		TerrainExtras.Recorder r = recorder.get();
		if (r != null) r.fluid(layers, fluid);
		original.call(renderer, pos, level, output, state, fluid);
		// The block's own model (a waterlogged one) comes next.
		if (r != null) r.block(layers, state);
	}

	@Inject(method = "compile", at = @At("RETURN"))
	private void afterburner$keepRuns(SectionPos sectionPos, RenderChunkRegion region, VertexSorting sorting, SectionBufferBuilderPack builders,
			CallbackInfoReturnable<SectionCompiler.Results> cir, @Share("afterburner$terrain") LocalRef<TerrainExtras.Recorder> recorder) {
		TerrainExtras.Recorder r = recorder.get();
		if (r != null) r.finish(cir.getReturnValue().renderedLayers);
	}
}
