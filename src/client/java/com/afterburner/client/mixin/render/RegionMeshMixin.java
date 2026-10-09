package com.afterburner.client.mixin.render;

import com.afterburner.client.render.BuiltSection;
import com.afterburner.client.render.RegionMesh;
import com.afterburner.client.render.RegionResults;
import com.afterburner.client.render.TerrainExtras;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompiledSectionMesh.class)
public class RegionMeshMixin implements RegionMesh, TerrainExtras.Holder {
	@Unique
	private int afterburner$regionSlot = -1;
	@Unique
	private long afterburner$regionKey = RegionMesh.NO_REGION;
	/** How each layer was uploaded, two bits each. */
	@Unique
	private int afterburner$formats;
	@Unique
	private int @Nullable [] @Nullable [] afterburner$blockRuns;
	/** Remembered buffer slices per layer; a layer's bit in {@code known} is set once it was looked up. */
	@Unique
	private SectionRenderDispatcher.@Nullable RenderSectionBufferSlice @Nullable [] afterburner$slices;
	@Unique
	private int afterburner$known;
	@Unique
	private @Nullable BuiltSection afterburner$owner;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void afterburner$takeRegion(TranslucencyPointOfView pointOfView, SectionCompiler.Results results, long startTime, CallbackInfo ci) {
		afterburner$regionSlot = ((RegionResults) (Object) results).afterburner$regionSlot();
		afterburner$regionKey = ((RegionResults) (Object) results).afterburner$regionKey();
		afterburner$blockRuns = ((TerrainExtras.Holder) (Object) results).afterburner$blockRuns();
	}

	@Override
	public boolean afterburner$inRegion() {
		return afterburner$regionSlot >= 0;
	}

	@Override
	public int afterburner$regionSlot() {
		return afterburner$regionSlot;
	}

	@Override
	public long afterburner$regionKey() {
		return afterburner$regionKey;
	}

	@Override
	public int afterburner$format(ChunkSectionLayer layer) {
		return afterburner$formats >> layer.ordinal() * 2 & 3;
	}

	@Override
	public void afterburner$setFormat(ChunkSectionLayer layer, int format) {
		int shift = layer.ordinal() * 2;
		afterburner$formats = afterburner$formats & ~(3 << shift) | format << shift;
	}

	@Override
	public int @Nullable [] @Nullable [] afterburner$blockRuns() {
		return afterburner$blockRuns;
	}

	@Override
	public void afterburner$setBlockRuns(int @Nullable [] @Nullable [] runs) {
		afterburner$blockRuns = runs;
	}

	@Override
	public SectionRenderDispatcher.@Nullable RenderSectionBufferSlice afterburner$slice(SectionRenderDispatcher dispatcher, ChunkSectionLayer layer) {
		int bit = 1 << layer.ordinal();
		if (afterburner$slices == null) afterburner$slices = new SectionRenderDispatcher.RenderSectionBufferSlice[ChunkSectionLayer.values().length];
		if ((afterburner$known & bit) == 0) {
			afterburner$slices[layer.ordinal()] = dispatcher.getRenderSectionSlice((CompiledSectionMesh) (Object) this, layer);
			afterburner$known |= bit;
		}
		return afterburner$slices[layer.ordinal()];
	}

	@Override
	public void afterburner$forgetSlices() {
		afterburner$known = 0;
	}

	@Override
	public @Nullable BuiltSection afterburner$owner() {
		return afterburner$owner;
	}

	@Override
	public void afterburner$setOwner(BuiltSection section) {
		afterburner$owner = section;
	}
}
