package com.afterburner.bench.client;

import com.afterburner.bench.Reports;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;

/**
 * How the render thread spends a frame while a benchmark measures: busy, or waiting in the two places it can wait for
 * the graphics card (submit, which waits for the frame before to finish, and present). Thread CPU time on Windows only
 * moves in scheduler ticks, so single frames mean nothing, but the totals over a run are right.
 */
public final class FrameWaits {
	private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
	public static boolean measuring;
	private static long frames, frameWall, frameCpu, submitWall, submitCpu, presentWall, presentCpu;
	private static long lastWall, lastCpu;
	/** Waited (wall time) in submit and present this frame, for {@link FrameSections}. */
	static long frameWaits;

	private FrameWaits() {
	}

	public static long cpu() {
		return THREADS.getCurrentThreadCpuTime();
	}

	public static void reset() {
		frames = frameWall = frameCpu = submitWall = submitCpu = presentWall = presentCpu = 0;
		lastWall = 0;
	}

	/** At the start of every frame: closes the one before. */
	public static void frameStart() {
		if (!measuring) {
			lastWall = 0;
			return;
		}
		long wall = System.nanoTime(), cpu = cpu();
		if (lastWall != 0) {
			frames++;
			frameWall += wall - lastWall;
			frameCpu += cpu - lastCpu;
		}
		lastWall = wall;
		lastCpu = cpu;
	}

	public static void submit(long wall, long cpu) {
		submitWall += wall;
		frameWaits += wall;
		submitCpu += cpu;
	}

	public static void present(long wall, long cpu) {
		presentWall += wall;
		frameWaits += wall;
		presentCpu += cpu;
	}

	/** The share of the frame the render thread spent waiting for the graphics card (wall time minus busy, in submit and present). */
	public static double gpuShare() {
		if (frameWall <= 0) return 0;
		return Math.max(0, submitWall - submitCpu + presentWall - presentCpu) / (double) frameWall;
	}

	public static List<String> lines() {
		if (frames == 0) return List.of();
		return List.of("Render thread per frame " + ms(frameWall) + " ms, busy " + ms(frameCpu) + " ms | submit (waits for the frame before) "
				+ ms(submitWall) + " ms, busy " + ms(submitCpu) + " | present " + ms(presentWall) + " ms, busy " + ms(presentCpu));
	}

	private static String ms(long total) {
		return Reports.f2(total / 1e6 / frames);
	}
}
