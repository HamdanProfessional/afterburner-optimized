package com.afterburner.bench.client;

import com.afterburner.bench.Reports;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How long the graphics card spends on each part of a frame, from OpenGL timestamps. Only with
 * {@code -Dafterburner.gpuTimers=true} (benchmarks); otherwise every call returns right away.
 * <p>
 * {@link #mark} stamps the time the card reaches that point; the time until the next mark goes to the mark's label.
 * Results are read a few frames later, when the card is done with them.
 */
public final class GpuTimers {
	private static final boolean REQUESTED = Boolean.getBoolean("afterburner.gpuTimers");
	private static final int FRAMES = 4, MARKS = 96;
	private static final int[][] queries = new int[FRAMES][MARKS];
	private static final String[][] labels = new String[FRAMES][MARKS];
	private static final int[] count = new int[FRAMES];
	private static final Map<String, long[]> totals = new LinkedHashMap<>();
	private static int frame;
	private static long frames, frameNanos;
	/** Whether the frames are added up (during the benchmark's measuring). */
	public static boolean measuring;
	private static Boolean on;

	private GpuTimers() {
	}

	private static boolean on() {
		if (on == null) on = REQUESTED && RenderSystem.getDevice().getDeviceInfo().backendName().toLowerCase().contains("gl");
		return on;
	}

	public static void mark(String label) {
		if (!REQUESTED || !on()) return;
		int f = frame % FRAMES, n = count[f];
		if (n == MARKS) return;
		if (queries[f][n] == 0) queries[f][n] = GL15C.glGenQueries();
		GL33C.glQueryCounter(queries[f][n], GL33C.GL_TIMESTAMP);
		labels[f][n] = label;
		count[f] = n + 1;
	}

	/** Start of a frame: adds up the frame from {@code FRAMES - 1} frames ago and starts timing this one. */
	public static void frameStart() {
		if (!REQUESTED || !on()) return;
		frame++;
		int f = frame % FRAMES;
		if (count[f] > 1 && measuring) collect(f);
		count[f] = 0;
		mark("frame start (uploads, lightmap)");
	}

	/** Called right before the frame is shown; the time after it (waiting for the screen) isn't counted. */
	public static void frameEnd() {
		mark("");
	}

	private static void collect(int f) {
		long first = GL33C.glGetQueryObjecti64(queries[f][0], GL15C.GL_QUERY_RESULT), prev = first;
		for (int i = 1; i < count[f]; i++) {
			long t = GL33C.glGetQueryObjecti64(queries[f][i], GL15C.GL_QUERY_RESULT);
			totals.computeIfAbsent(labels[f][i - 1], k -> new long[1])[0] += t - prev;
			prev = t;
			if (labels[f][i].isEmpty()) break;
		}
		frameNanos += prev - first;
		frames++;
	}

	public static void reset() {
		totals.clear();
		frames = frameNanos = 0;
	}

	/** Report lines, biggest first; empty if nothing was timed. */
	public static List<String> lines() {
		List<String> out = new ArrayList<>();
		if (frames == 0) return out;
		out.add("GPU time per frame " + Reports.f2(frameNanos / 1e6 / frames) + " ms:");
		totals.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).forEach(e -> out.add("  "
				+ Reports.f2(e.getValue()[0] / 1e6 / frames) + " ms  " + Reports.f1(100.0 * e.getValue()[0] / frameNanos) + "%  " + e.getKey()));
		return out;
	}
}
