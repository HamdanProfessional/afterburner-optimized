package com.afterburner.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Chunk sections are grouped in regions of 8 x 4 x 8 sections (128 x 64 x 128 blocks). Vanilla stores block positions
 * relative to each section's corner and tells the GPU where every section is with a draw call of its own. With the
 * positions stored relative to the region's corner instead, all sections of a region share one position, so they can
 * be drawn with one call.
 */
public final class ChunkRegions {
	private static final int MASK_XZ = -128, MASK_Y = -64;

	private ChunkRegions() {
	}

	public static int originX(int blockX) {
		return blockX & MASK_XZ;
	}

	public static int originY(int blockY) {
		return blockY & MASK_Y;
	}

	public static int originZ(int blockZ) {
		return blockZ & MASK_XZ;
	}

	/** The place of a section in its region, 0 to 255: x, y and z in the region in 3, 2 and 3 bits. */
	public static int slot(SectionPos section) {
		return section.x() & 7 | (section.y() & 3) << 3 | (section.z() & 7) << 5;
	}

	/** One number per region, for grouping. */
	public static long key(BlockPos sectionOrigin) {
		return key(sectionOrigin.getX(), sectionOrigin.getY(), sectionOrigin.getZ());
	}

	public static long key(int x, int y, int z) {
		return SectionPos.asLong(x >> 7, y >> 6, z >> 7);
	}

	/**
	 * Moves the vertices of every layer from section to region coordinates, right after the section was built. Returns
	 * false, changing nothing, if a layer doesn't use the vanilla block format (positions as the first three floats).
	 * Translucent quads were already sorted by then, from positions relative to the section, and later re-sorts reuse
	 * those, so they stay right.
	 */
	public static boolean moveToRegion(SectionPos section, Map<ChunkSectionLayer, MeshData> layers) {
		for (MeshData mesh : layers.values()) {
			if (mesh.drawState().format() != DefaultVertexFormat.BLOCK) return false;
		}
		float dx = section.minBlockX() & ~MASK_XZ, dy = section.minBlockY() & ~MASK_Y, dz = section.minBlockZ() & ~MASK_XZ;
		if (dx == 0 && dy == 0 && dz == 0) return true;
		int stride = DefaultVertexFormat.BLOCK.getVertexSize();
		for (MeshData mesh : layers.values()) {
			ByteBuffer v = mesh.vertexBuffer();
			int end = mesh.drawState().vertexCount() * stride;
			for (int at = 0; at < end; at += stride) {
				v.putFloat(at, v.getFloat(at) + dx);
				v.putFloat(at + 4, v.getFloat(at + 4) + dy);
				v.putFloat(at + 8, v.getFloat(at + 8) + dz);
			}
		}
		return true;
	}
}
