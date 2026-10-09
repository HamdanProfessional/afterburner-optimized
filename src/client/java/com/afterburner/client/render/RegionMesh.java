package com.afterburner.client.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jspecify.annotations.Nullable;

/** Added to compiled section meshes by chunk batching. */
public interface RegionMesh {
	/** What {@link #afterburner$regionKey} is for a mesh not built by the section compiler. */
	long NO_REGION = Long.MIN_VALUE;

	/** Whether the mesh's vertices are relative to its region's corner (see {@link ChunkRegions}) rather than its section's. */
	boolean afterburner$inRegion();

	/** The section's place in its region ({@link ChunkRegions#slot}), or -1 if not {@link #afterburner$inRegion}. */
	int afterburner$regionSlot();

	/** Which region the section is in ({@link ChunkRegions#key}), for keeping a region's meshes in one chunk buffer; or {@link #NO_REGION}. */
	long afterburner$regionKey();

	/** How the layer was uploaded: {@link CompactVertices#VANILLA}, {@link CompactVertices#COMPACT} or {@link CompactVertices#EXTENDED}. */
	int afterburner$format(ChunkSectionLayer layer);

	void afterburner$setFormat(ChunkSectionLayer layer, int format);

	/** Vanilla's {@code getRenderSectionSlice}, remembered until the mesh's buffers change instead of looked up twice per layer every frame. */
	SectionRenderDispatcher.@Nullable RenderSectionBufferSlice afterburner$slice(SectionRenderDispatcher dispatcher, ChunkSectionLayer layer);

	void afterburner$forgetSlices();

	/** The section the mesh was given to, told when the mesh moves in the chunk buffers. */
	@Nullable BuiltSection afterburner$owner();

	void afterburner$setOwner(BuiltSection section);
}
