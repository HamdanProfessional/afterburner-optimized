package com.afterburner.client.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.jspecify.annotations.Nullable;

/** Added to compiled section meshes: how each layer's quads were grouped by facing, if they were. */
public interface FaceSortedMesh {
	@Nullable SectionFaces afterburner$faces(ChunkSectionLayer layer);
}
