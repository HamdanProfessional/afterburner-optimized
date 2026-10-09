package com.afterburner.bench.client;

import com.afterburner.Afterburner;

/**
 * With {@code -Dafterburner.buildLog=true}, the benchmark warmup logs once a second what made chunk sections build:
 * chunks arriving, light updates, block changes, and how many first builds and rebuilds that came to.
 */
public final class BuildLog {
	public static final boolean ENABLED = Boolean.getBoolean("afterburner.buildLog");
	/** Render thread only. */
	public static int chunkMarks, lightMarks, blockMarks, firstBuilds, rebuilds, busyFrames, scans, offscreen;
	public static long scanNanos;
	private static int second = -1;

	private BuildLog() {
	}

	/** Each warmup frame. */
	public static void frame(long sinceJoin, int chunks, boolean busy, int queue) {
		if (busy) busyFrames++;
		int s = (int) (sinceJoin / 1_000_000_000L);
		if (s == second) return;
		if (second >= 0) {
			Afterburner.LOGGER.info("Build log {} s: {} chunks, busy {} frames, queue {} | marked dirty by chunks {}, light {}, blocks {} | first builds {}, rebuilds {}"
					+ " | off-screen {} from {} scans, {} us each", second, chunks, busyFrames, queue, chunkMarks, lightMarks, blockMarks, firstBuilds, rebuilds,
					offscreen, scans, scans == 0 ? 0 : scanNanos / scans / 1000);
		}
		second = s;
		chunkMarks = lightMarks = blockMarks = firstBuilds = rebuilds = busyFrames = scans = offscreen = 0;
		scanNanos = 0;
	}
}
