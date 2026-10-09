package com.afterburner.client.mixin.render;

import com.afterburner.client.render.RegionMesh;
import com.afterburner.client.render.RegionResults;
import com.afterburner.client.render.TerrainExtras;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SectionCompiler.Results.class)
public class SectionResultsMixin implements RegionResults, TerrainExtras.Holder {
	@Unique
	private int afterburner$regionSlot = -1;
	@Unique
	private long afterburner$regionKey = RegionMesh.NO_REGION;
	@Unique
	private int @Nullable [] @Nullable [] afterburner$blockRuns;

	@Override
	public int @Nullable [] @Nullable [] afterburner$blockRuns() {
		return afterburner$blockRuns;
	}

	@Override
	public void afterburner$setBlockRuns(int @Nullable [] @Nullable [] runs) {
		afterburner$blockRuns = runs;
	}

	@Override
	public int afterburner$regionSlot() {
		return afterburner$regionSlot;
	}

	@Override
	public void afterburner$setRegionSlot(int slot) {
		afterburner$regionSlot = slot;
	}

	@Override
	public long afterburner$regionKey() {
		return afterburner$regionKey;
	}

	@Override
	public void afterburner$setRegionKey(long key) {
		afterburner$regionKey = key;
	}
}
