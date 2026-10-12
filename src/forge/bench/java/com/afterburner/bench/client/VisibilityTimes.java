package com.afterburner.bench.client;

import com.afterburner.bench.Reports;

import java.util.concurrent.atomic.AtomicLong;

/** How long the occlusion graph's visibility updates take, for benchmark reports. Full ones run on a worker thread, partial ones on the render thread. */
public final class VisibilityTimes {
	private static final AtomicLong FULL = new AtomicLong(), FULL_NANOS = new AtomicLong();
	private static final AtomicLong PARTIAL = new AtomicLong(), PARTIAL_NANOS = new AtomicLong();

	private VisibilityTimes() {
	}

	public static void ran(boolean full, long nanos) {
		(full ? FULL : PARTIAL).incrementAndGet();
		(full ? FULL_NANOS : PARTIAL_NANOS).addAndGet(nanos);
	}

	public static void reset() {
		FULL.set(0);
		FULL_NANOS.set(0);
		PARTIAL.set(0);
		PARTIAL_NANOS.set(0);
	}

	/** E.g. "Visibility updates: 41 full, avg 38.2 ms each (1.6 s); 1,203 partial, avg 95 us each". */
	public static String line() {
		long full = FULL.get(), fullNanos = FULL_NANOS.get(), partial = PARTIAL.get(), partialNanos = PARTIAL_NANOS.get();
		return "Visibility updates: " + String.format("%,d", full) + " full, avg " + Reports.f1(full == 0 ? 0 : fullNanos / 1e6 / full) + " ms each ("
				+ Reports.f1(fullNanos / 1e9) + " s); " + String.format("%,d", partial) + " partial, avg "
				+ (partial == 0 ? 0 : partialNanos / partial / 1000) + " us each";
	}
}
