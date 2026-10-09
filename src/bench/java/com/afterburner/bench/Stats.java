package com.afterburner.bench;

import java.util.Arrays;

/** A summary of a list of durations (frame times or tick times), all in milliseconds. */
public record Stats(int count, double totalMs, double avgMs, double p95Ms, double p99Ms, double p999Ms, double maxMs, int over50Ms) {
	public static Stats of(long[] nanos) {
		if (nanos.length == 0) {
			return new Stats(0, 0, 0, 0, 0, 0, 0, 0);
		}
		long[] sorted = nanos.clone();
		Arrays.sort(sorted);
		long total = 0;
		int over50 = 0;
		for (long n : sorted) {
			total += n;
			if (n > 50_000_000L) over50++;
		}
		return new Stats(sorted.length, ms(total), ms(total) / sorted.length, ms(percentile(sorted, 0.95)),
				ms(percentile(sorted, 0.99)), ms(percentile(sorted, 0.999)), ms(sorted[sorted.length - 1]), over50);
	}

	/** Frames per second if every frame took {@code ms}. */
	public static double fps(double ms) {
		return ms > 0 ? 1000.0 / ms : 0;
	}

	private static long percentile(long[] sorted, double p) {
		int i = (int) Math.ceil(p * sorted.length) - 1;
		return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
	}

	private static double ms(long nanos) {
		return nanos / 1_000_000.0;
	}
}
