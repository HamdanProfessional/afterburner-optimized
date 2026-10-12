package com.afterburner.bench;

import java.util.concurrent.atomic.AtomicLong;

/** How long the light threads spent running light batches, for the chunk and light benchmarks. */
public final class LightStats {
	private static final AtomicLong NANOS = new AtomicLong();
	private static final AtomicLong BATCHES = new AtomicLong();
	private static final AtomicLong STEPS = new AtomicLong();

	private LightStats() {
	}

	public static void add(long nanos) {
		NANOS.addAndGet(nanos);
		BATCHES.incrementAndGet();
	}

	/** Light changes spread by a batch (each block the light moved through, once per direction it was queued in). */
	public static void addSteps(int steps) {
		STEPS.addAndGet(steps);
	}

	public static long nanos() {
		return NANOS.get();
	}

	public static long batches() {
		return BATCHES.get();
	}

	public static long steps() {
		return STEPS.get();
	}
}
