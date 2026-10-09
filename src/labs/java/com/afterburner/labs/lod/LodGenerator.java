package com.afterburner.labs.lod;

import com.afterburner.labs.worldgen.FarLand;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Far terrain where no chunk was ever seen, made from the world's seed ({@link FarLand}): the ground's shape, its biomes and
 * surface blocks, the sea, and (nearer than the farthest levels) its trees; no caves or buildings. Single player only (it needs the integrated server's generator),
 * and only where the generator can do it (not the Nether or the End).
 * <p>
 * When the worker ({@link LodWorker}) is to build a node with sheets missing, it hands them here and waits; this makes
 * them on a thread of its own, nearest the player first and only while the renderer still wants the node, and hands
 * each back to the worker, which puts it in where nothing was seen. What was seen always wins: a chunk that comes in
 * later replaces what was made.
 */
final class LodGenerator {
	private static final Logger LOGGER = LoggerFactory.getLogger("afterburner");
	/** {@code -Dafterburner.farLand=false}: nothing is made, whatever the setting. */
	private static final boolean ALLOWED = !"false".equals(System.getProperty("afterburner.farLand"));
	/** For the worker's stats: sheets made, and the time it took. */
	static final AtomicLong MADE = new AtomicLong(), MADE_NANOS = new AtomicLong();

	/** A node's missing sheets. */
	static final class Job {
		final LodWorld world;
		final int level, nx, nz;
		/** The renderer's request for the node: when it goes (the player moved on), the job goes too. */
		final LodWorker.Request request;
		/** The sheets to make, by place in the node ({@code dz * 8 + dx}). */
		final int[] sheets;
		int next;
		/** Set by the worker once the sheets made are in. */
		volatile boolean done;

		Job(LodWorker.Request request, int[] sheets) {
			this.world = request.world;
			this.level = request.level;
			this.nx = request.nx;
			this.nz = request.nz;
			this.request = request;
			this.sheets = sheets;
		}
	}

	/** A sheet made, at the job's level; null when the job is over (all made, or given up). */
	record Made(Job job, int sx, int sz, FarLand.@Nullable Sheet sheet) {
	}

	private final LodWorker worker;
	private final LodWorld world;
	private @Nullable ServerLevel level;
	/** Per node ({@link LodWorld#key}); guarded by itself. */
	private final Long2ObjectOpenHashMap<Job> jobs = new Long2ObjectOpenHashMap<>();
	private @Nullable Thread thread;
	private volatile boolean stopping, failed;
	private int errors;

	private LodGenerator(LodWorker worker, LodWorld world, ServerLevel level) {
		this.worker = worker;
		this.world = world;
		this.level = level;
	}

	/** Null in multiplayer, or where the land can't be made. */
	static @Nullable LodGenerator create(LodWorker worker, LodWorld world, ClientLevel clientLevel) {
		if (!ALLOWED) return null;
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) return null;
		ServerLevel level = server.getLevel(clientLevel.dimension());
		if (level == null || !FarLand.possible(level)) return null;
		return new LodGenerator(worker, world, level);
	}

	/** Whether it takes jobs: it's on, and hasn't failed or stopped. */
	boolean usable() {
		return LodSettings.unseenLand() && !failed && !stopping;
	}

	/** Worker thread: makes the request's missing sheets (by place in its node, {@code dz * 8 + dx}). */
	void submit(LodWorker.Request request, int[] sheets) {
		Job job = new Job(request, sheets);
		request.job = job;
		synchronized (jobs) {
			Job old = jobs.put(LodWorld.key(job.level, job.nx, job.nz), job);
			if (old != null) worker.made(new Made(old, 0, 0, null));
			if (thread == null) {
				Thread t = new Thread(this::run, "Afterburner far land");
				t.setDaemon(true);
				// Like the far terrain's worker: below the game's own threads.
				t.setPriority(Thread.MIN_PRIORITY);
				thread = t;
				t.start();
			}
		}
		LockSupport.unpark(thread);
	}

	/** Stops making land (the world's closing), waiting a moment for the sheet being made. */
	void stop() {
		stopping = true;
		Thread t;
		synchronized (jobs) {
			t = thread;
			if (t == null) level = null;
		}
		if (t == null) return;
		LockSupport.unpark(t);
		try {
			t.join(250);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void run() {
		try {
			work();
		} catch (Throwable t) {
			failed = true;
			LOGGER.error("[Afterburner] Making far land failed; it's off for this world", t);
		} finally {
			// Whatever's left is built as it is.
			synchronized (jobs) {
				for (Job job : jobs.values()) worker.made(new Made(job, 0, 0, null));
				jobs.clear();
			}
		}
	}

	private void work() {
		FarLand.Sampler sampler = null;
		while (!stopping && !failed) {
			Job job = next();
			if (job == null) {
				LockSupport.parkNanos(50_000_000L);
				continue;
			}
			try {
				if (sampler == null) {
					sampler = setUp();
					if (sampler == null) {
						failed = true;
						break;
					}
				}
				int i = job.sheets[job.next++];
				int sx = job.nx * LodMesher.SHEETS + (i & 7), sz = job.nz * LodMesher.SHEETS + (i >> 3);
				int span = LodSheet.SIZE << job.level;
				long t0 = System.nanoTime();
				FarLand.Sheet sheet = sampler.sheet(job.level, sx * span, sz * span);
				MADE_NANOS.addAndGet(System.nanoTime() - t0);
				MADE.incrementAndGet();
				worker.made(new Made(job, sx, sz, sheet));
			} catch (Throwable t) {
				if (errors++ < 10) LOGGER.error("[Afterburner] Making far land failed", t);
				if (errors >= 10) failed = true;
				// The sheet's left out: the node's built without it.
			}
			if (job.next >= job.sheets.length || failed) finish(job);
		}
	}

	/** The land's maker; null (and logged) if it can't be set up, or doesn't fit the far terrain's world. */
	private FarLand.@Nullable Sampler setUp() {
		ServerLevel l = level;
		level = null;
		if (l == null) return null;
		try {
			FarLand land = FarLand.of(l);
			if (land == null) return null;
			if (land.minY != world.minY || land.topY != world.minY + world.height) {
				LOGGER.info("[Afterburner] Not making far land: the generator's height ({} to {}) isn't the world's ({} to {})",
						land.minY, land.topY, world.minY, world.minY + world.height);
				return null;
			}
			return land.sampler();
		} catch (RuntimeException e) {
			LOGGER.error("[Afterburner] Couldn't set up making far land; it's off for this world", e);
			return null;
		}
	}

	/** The wanted job nearest the player; jobs whose node nobody wants any more are let go. */
	private @Nullable Job next() {
		boolean on = LodSettings.unseenLand();
		List<Job> gone = null;
		Job best = null;
		synchronized (jobs) {
			for (Iterator<Job> it = jobs.values().iterator(); it.hasNext(); ) {
				Job job = it.next();
				boolean wanted = on && worker.requests.get(LodWorld.key(job.level, job.nx, job.nz)) == job.request;
				if (!wanted) {
					it.remove();
					if (gone == null) gone = new ArrayList<>();
					gone.add(job);
					continue;
				}
				if (best == null || job.request.priority < best.request.priority) best = job;
			}
		}
		if (gone != null) for (Job job : gone) worker.made(new Made(job, 0, 0, null));
		return best;
	}

	private void finish(Job job) {
		synchronized (jobs) {
			jobs.remove(LodWorld.key(job.level, job.nx, job.nz), job);
		}
		worker.made(new Made(job, 0, 0, null));
	}
}
