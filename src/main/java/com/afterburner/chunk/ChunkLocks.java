package com.afterburner.chunk;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Runs chunk jobs on many threads at once without two of them touching the same chunk at the same time. Each job
 * names the square of chunks it writes and a wider square it reads. It starts once no running job writes anything
 * it touches and no running job reads anything it writes. Jobs that have to wait start in the order they came in,
 * except that a later job can go first if it doesn't overlap anything running.
 * <p>
 * Only the job itself holds the chunks. If it returns a future that finishes later (lighting does), the chunks are
 * free as soon as the job returns.
 */
final class ChunkLocks {
	private final Executor executor;
	/** Chunks a running job writes. */
	private final LongOpenHashSet writing = new LongOpenHashSet();
	/** How many running jobs read each chunk (only the outer ring, the write square counts as writing). */
	private final Long2IntOpenHashMap reading = new Long2IntOpenHashMap();
	private final LinkedList<Job<?>> waiting = new LinkedList<>();

	ChunkLocks(Executor executor) {
		this.executor = executor;
	}

	<T> CompletableFuture<T> run(ChunkPos center, int writeRadius, int readRadius, Supplier<CompletableFuture<T>> work) {
		Job<T> job = new Job<>(center.x(), center.z(), writeRadius, Math.max(writeRadius, readRadius), work);
		List<Job<?>> ready;
		synchronized (this) {
			waiting.add(job);
			ready = takeReady();
		}
		for (Job<?> r : ready) executor.execute(r);
		return job.result;
	}

	synchronized int waitingCount() {
		return waiting.size();
	}

	/** Claims and returns every waiting job that can start now. */
	private List<Job<?>> takeReady() {
		List<Job<?>> ready = new ArrayList<>(2);
		for (Iterator<Job<?>> it = waiting.iterator(); it.hasNext(); ) {
			Job<?> job = it.next();
			if (canStart(job)) {
				it.remove();
				claim(job, true);
				ready.add(job);
			}
		}
		return ready;
	}

	private boolean canStart(Job<?> job) {
		for (int dz = -job.read; dz <= job.read; dz++) {
			for (int dx = -job.read; dx <= job.read; dx++) {
				long pos = ChunkPos.pack(job.x + dx, job.z + dz);
				if (writing.contains(pos)) return false;
				if (job.writes(dx, dz) && reading.get(pos) > 0) return false;
			}
		}
		return true;
	}

	private void claim(Job<?> job, boolean take) {
		for (int dz = -job.read; dz <= job.read; dz++) {
			for (int dx = -job.read; dx <= job.read; dx++) {
				long pos = ChunkPos.pack(job.x + dx, job.z + dz);
				if (job.writes(dx, dz)) {
					if (take) writing.add(pos);
					else writing.remove(pos);
				} else if (take) {
					reading.addTo(pos, 1);
				} else if (reading.addTo(pos, -1) == 1) {
					reading.remove(pos);
				}
			}
		}
	}

	private void finish(Job<?> job) {
		List<Job<?>> ready;
		synchronized (this) {
			claim(job, false);
			ready = takeReady();
		}
		for (Job<?> r : ready) executor.execute(r);
	}

	private final class Job<T> implements Runnable {
		final int x, z, write, read;
		final Supplier<CompletableFuture<T>> work;
		final CompletableFuture<T> result = new CompletableFuture<>();

		Job(int x, int z, int write, int read, Supplier<CompletableFuture<T>> work) {
			this.x = x;
			this.z = z;
			this.write = write;
			this.read = read;
			this.work = work;
		}

		boolean writes(int dx, int dz) {
			return Math.abs(dx) <= write && Math.abs(dz) <= write;
		}

		@Override
		public void run() {
			CompletableFuture<T> future;
			try {
				future = work.get();
			} catch (Throwable t) {
				finish(this);
				result.completeExceptionally(t);
				return;
			}
			finish(this);
			future.whenComplete((value, error) -> {
				if (error != null) result.completeExceptionally(error);
				else result.complete(value);
			});
		}
	}
}
