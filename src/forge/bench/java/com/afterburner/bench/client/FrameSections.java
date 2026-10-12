package com.afterburner.bench.client;

import com.afterburner.bench.Reports;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.metrics.MetricCategory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * Per frame while a benchmark measures: the time the render thread worked, leaving out its waits for the graphics card
 * ({@link FrameWaits}), and the time in each of the game's profiler sections. On a fast graphics card the frame takes
 * about its work time, so these give the 1% lows such a card would see even on a slow one, and what the slowest frames
 * spent their time on.
 */
public final class FrameSections implements ProfilerFiller {
	public static final FrameSections INSTANCE = new FrameSections();
	public static boolean measuring;
	/** Sections deeper than this are counted in their parent. */
	private static final int DEPTH = 7;

	private final Object2IntOpenHashMap<String> ids = new Object2IntOpenHashMap<>();
	private final List<String> paths = new ArrayList<>();
	private final String[] stack = new String[64];
	private final long[] started = new long[64];
	private int depth;
	private long tickStart;
	private long[] current = new long[64];
	private final List<long[]> frames = new ArrayList<>();
	private final List<Long> work = new ArrayList<>();

	private FrameSections() {
		ids.defaultReturnValue(-1);
	}

	public static void reset() {
		INSTANCE.frames.clear();
		INSTANCE.work.clear();
	}

	@Override
	public void startTick() {
		depth = 0;
		tickStart = System.nanoTime();
		Arrays.fill(current, 0);
		FrameWaits.frameWaits = 0;
	}

	@Override
	public void endTick() {
		while (depth > 0) pop();
		if (!measuring) return;
		work.add(System.nanoTime() - tickStart - FrameWaits.frameWaits);
		frames.add(Arrays.copyOf(current, paths.size()));
	}

	@Override
	public void push(String name) {
		if (depth < stack.length) {
			stack[depth] = depth == 0 ? name : depth < DEPTH ? stack[depth - 1] + "." + name : stack[depth - 1];
			started[depth] = System.nanoTime();
		}
		depth++;
	}

	@Override
	public void push(Supplier<String> name) {
		push(depth < DEPTH ? name.get() : "");
	}

	@Override
	public void pop() {
		if (depth == 0) return;
		depth--;
		if (depth >= stack.length) return;
		// A section too deep to have its own path is already counted in its parent's time.
		if (depth >= DEPTH) return;
		long took = System.nanoTime() - started[depth];
		String path = stack[depth];
		int id = ids.getInt(path);
		if (id < 0) {
			id = paths.size();
			ids.put(path, id);
			paths.add(path);
			if (current.length <= id) current = Arrays.copyOf(current, current.length * 2);
		}
		current[id] += took;
	}

	@Override
	public void popPush(String name) {
		pop();
		push(name);
	}

	@Override
	public void popPush(Supplier<String> name) {
		pop();
		push(name);
	}

	@Override
	public void markForCharting(MetricCategory category) {
	}

	@Override
	public void incrementCounter(String name, int amount) {
	}

	@Override
	public void incrementCounter(Supplier<String> name, int amount) {
	}

	/** Work time per frame in nanoseconds, sorted. */
	private long[] sortedWork() {
		long[] w = new long[work.size()];
		for (int i = 0; i < w.length; i++) w[i] = work.get(i);
		Arrays.sort(w);
		return w;
	}

	/** {average FPS, 1% low, 0.1% low} from the work times, as if the graphics card never made the frame wait. */
	public static double[] fps() {
		long[] w = INSTANCE.sortedWork();
		if (w.length == 0) return new double[3];
		double sum = 0;
		for (long t : w) sum += t;
		return new double[] {1e9 / (sum / w.length), 1e9 / w[(int) Math.min(w.length - 1, Math.floor(w.length * 0.99))],
				1e9 / w[(int) Math.min(w.length - 1, Math.floor(w.length * 0.999))]};
	}

	public static List<String> lines() {
		FrameSections s = INSTANCE;
		int n = s.work.size();
		if (n < 100) return List.of();
		long[] w = s.sortedWork();
		double[] fps = fps();
		List<String> lines = new ArrayList<>();
		lines.add("Without waiting for the graphics card (as on a fast one): work per frame avg " + Reports.f2(1e3 / fps[0]) + " ms, 99% "
				+ Reports.f2(w[(int) (n * 0.99)] / 1e6) + ", 99.9% " + Reports.f2(w[(int) Math.min(n - 1, n * 0.999)] / 1e6) + ", max "
				+ Reports.f2(w[n - 1] / 1e6) + " | FPS " + Reports.f0(fps[0]) + ", 1% low " + Reports.f0(fps[1]) + ", 0.1% low " + Reports.f0(fps[2]));
		// The slowest 1% of frames against all frames, section by section.
		long limit = w[(int) (n * 0.99)];
		int paths = s.paths.size();
		double[] all = new double[paths], slow = new double[paths];
		int slowCount = 0;
		for (int f = 0; f < n; f++) {
			long[] t = s.frames.get(f);
			boolean isSlow = s.work.get(f) >= limit;
			if (isSlow) slowCount++;
			for (int p = 0; p < t.length; p++) {
				all[p] += t[p];
				if (isSlow) slow[p] += t[p];
			}
		}
		Integer[] order = new Integer[paths];
		double[] grew = new double[paths];
		for (int p = 0; p < paths; p++) {
			order[p] = p;
			grew[p] = slow[p] / Math.max(1, slowCount) - all[p] / n;
		}
		Arrays.sort(order, (a, b) -> Double.compare(grew[b], grew[a]));
		StringBuilder line = new StringBuilder("Slowest 1% of frames (" + slowCount + ", over " + Reports.f2(limit / 1e6) + " ms of work) spent more than usual in:");
		for (int i = 0; i < Math.min(20, paths); i++) {
			int p = order[i];
			if (grew[p] < 50_000) break;
			line.append("\n  ").append(s.paths.get(p)).append(" +").append(Reports.f2(grew[p] / 1e6)).append(" ms (usually ")
					.append(Reports.f2(all[p] / n / 1e6)).append(")");
		}
		lines.add(line.toString());
		return lines;
	}
}
