package com.afterburner.client.render;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What a mesh or index buffer was built for: its task's cancel flag (a cancelled build's upload is dropped), the
 * section's position, and for translucent ones the sort state their indices were made from, the index type, and the
 * split of their indices into the faces turned to the camera and those turned away ({@link TranslucentCulling#take}, or -1).
 */
public record BuildStamp(AtomicBoolean cancelled, long section, MeshData.@Nullable SortState sortState, VertexFormat.IndexType indexType, long split) {
	/** How many indices from the start face the camera, for as long as its point of view {@link TranslucentCulling#holds}. */
	public int frontIndices() {
		return split < 0 ? 0 : (int) (split >>> 32);
	}

	public int pointOfView() {
		return split < 0 ? TranslucentCulling.NO_PARTITION : (int) split;
	}
}
