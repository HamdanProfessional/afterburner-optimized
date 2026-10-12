package com.afterburner.client.mixin.render;

import com.afterburner.client.render.TerrainExtras;
import com.mojang.blaze3d.vertex.MeshData;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** A section layer's block runs ({@link TerrainExtras}), on its mesh from the build to its upload. */
@Mixin(MeshData.class)
public class TerrainMeshMixin implements TerrainExtras.Holder {
	@Unique
	private int @Nullable [] afterburner$blockRuns;

	@Override
	public int @Nullable [] afterburner$blockRuns() {
		return afterburner$blockRuns;
	}

	@Override
	public void afterburner$setBlockRuns(int @Nullable [] runs) {
		afterburner$blockRuns = runs;
	}
}
