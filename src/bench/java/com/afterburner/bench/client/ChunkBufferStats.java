package com.afterburner.bench.client;

import com.afterburner.client.render.ChunkBatcher;
import net.minecraft.client.Minecraft;

/**
 * How much of the chunk batcher's GPU buffers (one vertex and one index buffer per region) is reserved, and how much is
 * really used. With the batcher off each section has buffers of its own, sized to fit, so there's nothing to report.
 */
public final class ChunkBufferStats {
	private ChunkBufferStats() {
	}

	public static String line(Minecraft mc) {
		if (!ChunkBatcher.ENABLED) return "Chunk GPU buffers: the chunk batcher is off (each section has its own)";
		long[] s = ChunkBatcher.bufferStats();
		return "Chunk GPU buffers (reserved/used MB): " + s[0] + " regions, vertices " + (s[1] >> 20) + '/' + (s[2] >> 20) + ", indices "
				+ (s[3] >> 20) + '/' + (s[4] >> 20) + " | total " + (s[1] + s[3] >> 20) + '/' + (s[2] + s[4] >> 20);
	}
}
