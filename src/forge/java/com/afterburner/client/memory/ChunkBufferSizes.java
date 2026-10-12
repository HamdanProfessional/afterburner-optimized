package com.afterburner.client.memory;

/**
 * How big a new piece of the chunk renderer's GPU memory is. For each block layer (solid, cutout, translucent) vanilla
 * reserves 128 MB pieces of vertex memory (32 MB for indices), the first one as soon as one chunk section of that layer
 * is drawn, so at render distance 12 about 416 MB are reserved for about 160 MB of meshes. On a laptop's built-in
 * graphics that is plain RAM. Here pieces start at an eighth of vanilla's size and then grow with what is already
 * reserved (by a quarter of it each time, up to vanilla's size), so at most about a quarter is left empty.
 */
public final class ChunkBufferSizes {
	private static final long MB = 1 << 20;

	private ChunkBufferSizes() {
	}

	/**
	 * @param vanillaSize the size vanilla gives every piece
	 * @param reserved    the size of the pieces this buffer already has
	 * @param needed      the mesh that didn't fit into them
	 */
	public static long heapSize(long vanillaSize, long reserved, long needed) {
		long size = Math.min(vanillaSize, Math.max(vanillaSize / 8, reserved / 4));
		// The allocator rounds a request up by up to an eighth and pads it for alignment, so leave room for that.
		long fit = needed + needed / 4 + MB;
		size = Math.max(size, fit);
		return (size + MB - 1) / MB * MB;
	}
}
