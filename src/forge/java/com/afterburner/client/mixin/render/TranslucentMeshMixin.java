package com.afterburner.client.mixin.render;

import com.afterburner.client.render.TranslucentCulling;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** The split of a section's uploaded translucent index buffer (see {@link TranslucentCulling}). Render thread only. */
@Mixin(CompiledSectionMesh.class)
public class TranslucentMeshMixin implements TranslucentCulling.Partitioned {
	@Unique
	private int afterburner$frontIndices;
	@Unique
	private int afterburner$pointOfView = TranslucentCulling.NO_PARTITION;

	@Override
	public int afterburner$frontIndices() {
		return afterburner$frontIndices;
	}

	@Override
	public int afterburner$pointOfView() {
		return afterburner$pointOfView;
	}

	@Override
	public void afterburner$setPartition(int frontIndices, int pointOfView) {
		afterburner$frontIndices = frontIndices;
		afterburner$pointOfView = pointOfView;
	}
}
