package com.afterburner.bench.client;

import com.afterburner.bench.Reports;

import java.util.concurrent.atomic.AtomicLong;

/** How long chunk section builds take on the builder threads, for benchmark reports. */
public final class BuildTimes {
	private static final AtomicLong BUILDS = new AtomicLong();
	private static final AtomicLong NANOS = new AtomicLong();

	private BuildTimes() {
	}

	public static void built(long nanos) {
		BUILDS.incrementAndGet();
		NANOS.addAndGet(nanos);
	}

	public static void reset() {
		BUILDS.set(0);
		NANOS.set(0);
	}

	/** E.g. "Section builds: 12,345, avg 412 us each (5.1 s of builder time)". */
	public static String line() {
		long builds = BUILDS.get(), nanos = NANOS.get();
		return "Section builds: " + String.format("%,d", builds) + ", avg " + (builds == 0 ? 0 : nanos / builds / 1000) + " us each ("
				+ Reports.f1(nanos / 1e9) + " s of builder time)";
	}
}
