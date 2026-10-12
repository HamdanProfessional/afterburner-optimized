package com.afterburner.client.mixin.render;

import com.afterburner.client.render.ChunkBatcher;
import com.afterburner.client.render.ChunkRegions;
import com.afterburner.client.render.CompactVertices;
import com.afterburner.client.render.Probe;
import com.afterburner.client.render.TerrainExtras;
import com.afterburner.client.render.RegionMesh;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.Util;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hands the chunk draws of every frame to {@link ChunkBatcher}. */
@Mixin(LevelRenderer.class)
public class ChunkBatchingMixin {
	@Shadow
	@Final
	private TextureManager textureManager;
	@Shadow
	@Final
	private OptionsRenderState optionsRenderState;
	@Shadow
	@Final
	private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
	@Shadow
	private @Nullable SectionRenderDispatcher sectionRenderDispatcher;
	@Shadow
	@Final
	private LevelRenderState levelRenderState;

	@Inject(method = "prepareChunkRenders", at = @At("HEAD"), cancellable = true)
	private void afterburner$batch(Matrix4fc modelView, boolean respectTranslucentOrder, CallbackInfoReturnable<ChunkSectionsToRender> cir) {
		Probe.get().chunksInView(visibleSections, levelRenderState.cameraRenderState);
		cir.setReturnValue(ChunkBatcher.prepare(visibleSections, sectionRenderDispatcher, Util.toMillis(optionsRenderState.chunkSectionFadeInTime),
				Util.getMillis(), levelRenderState.cameraRenderState, textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView(),
				modelView, respectTranslucentOrder));
	}

	/**
	 * Vanilla's indirect multi-draw (graphics cards other than Intel's) can't draw {@link CompactVertices} (or terrain built
	 * for a shader pack), so with those on, chunks are drawn by the batcher on every card.
	 */
	@Inject(method = "prepareChunkRendersIndirect", at = @At("HEAD"), cancellable = true)
	private void afterburner$batchIndirect(Matrix4fc modelView, boolean respectTranslucentOrder, CallbackInfoReturnable<ChunkSectionsToRender> cir) {
		if (CompactVertices.ENABLED || TerrainExtras.used()) afterburner$batch(modelView, respectTranslucentOrder, cir);
	}

	/** Vanilla's multi-draw path (graphics cards other than Intel's) tells the GPU where each section is: for moved meshes, their region's corner. */
	@WrapOperation(method = "extractSectionDrawGroups", at = @At(value = "NEW", target = "net/minecraft/client/renderer/DynamicGpuData$ChunkSectionInfo"))
	private DynamicGpuData.ChunkSectionInfo afterburner$regionCorner(int x, int y, int z, float visibility, Operation<DynamicGpuData.ChunkSectionInfo> original,
			@Local SectionMesh mesh) {
		if (mesh instanceof RegionMesh regional && regional.afterburner$inRegion()) {
			return original.call(ChunkRegions.originX(x), ChunkRegions.originY(y), ChunkRegions.originZ(z), visibility);
		}
		return original.call(x, y, z, visibility);
	}
}
