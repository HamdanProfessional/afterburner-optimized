package com.afterburner.chunk;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Vanilla runs the worldgen steps that finish right away (placing trees, ores and structure pieces, the
 * "features" step) one chunk at a time on a single thread, and in a big world that one thread is what everything
 * else waits on. This runs them on all worker threads, using {@link ChunkLocks} so two chunks never write the same
 * blocks at once.
 * <p>
 * Features write up to 1 chunk out and read up to 2 out, so a features job claims its 3x3 for writing and the ring
 * around that for reading. Light setup reads a chunk's blocks, so it also has to wait for features next door.
 * Everything else stays as vanilla runs it.
 */
public final class ParallelWorldgen {
	private static final Map<ServerLevel, ChunkLocks> LOCKS = new WeakHashMap<>();

	private ParallelWorldgen() {
	}

	public static CompletableFuture<ChunkAccess> apply(ChunkStep step, ServerLevel level, ChunkAccess chunk,
			Supplier<CompletableFuture<ChunkAccess>> work) {
		if (!chunk.getPersistedStatus().isBefore(step.targetStatus())) {
			return work.get();
		}
		int write, read;
		if (step.blockStateWriteRadius() > 0) {
			write = step.blockStateWriteRadius();
			read = write + 1;
		} else if (step.targetStatus() == ChunkStatus.INITIALIZE_LIGHT) {
			write = 0;
			read = 0;
		} else {
			return work.get();
		}
		ChunkPos pos = chunk.getPos();
		return locks(level).run(pos, write, read, () -> {
			try {
				return work.get();
			} catch (RuntimeException e) {
				throw new IllegalStateException("Generating " + step.targetStatus().getName() + " for chunk " + pos.x()
						+ ", " + pos.z() + " failed (Afterburner runs this step in parallel; -Dafterburner.parallelWorldgen=false turns that off)", e);
			}
		});
	}

	private static ChunkLocks locks(ServerLevel level) {
		synchronized (LOCKS) {
			return LOCKS.computeIfAbsent(level, l -> new ChunkLocks(Util.backgroundExecutor()));
		}
	}
}
