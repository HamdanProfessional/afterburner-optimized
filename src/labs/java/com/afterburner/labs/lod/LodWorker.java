package com.afterburner.labs.lod;

import com.afterburner.labs.worldgen.FarLand;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * The far terrain's one background thread: turns chunk copies into sheets and the levels above them, builds node meshes
 * the renderer asks for (nearest first), and saves. Everything a {@link LodWorld} holds is touched only here. A node
 * with sheets missing waits, in single player, for its world's {@link LodGenerator} to make them from the seed.
 */
final class LodWorker {
	private static final Logger LOGGER = LoggerFactory.getLogger("afterburner");
	/** Chunk copies waiting may take this much memory; past it, chunks that leave aren't kept. */
	private static final long SNAPSHOT_LIMIT = 128L << 20;
	private static final long SAVE_EVERY = 30_000_000_000L;
	/** {@code -Dafterburner.lodStats=true}: logs how long converting chunks and building nodes take (for benchmarks). */
	private static final boolean STATS = Boolean.getBoolean("afterburner.lodStats");

	/** A node the renderer wants a mesh of; {@code priority} (lower first) is updated while it waits. */
	static final class Request {
		final LodWorld world;
		final int level, nx, nz;
		volatile double priority;
		volatile int frame;
		final AtomicBoolean taken = new AtomicBoolean();
		/** The land being made for it; until that's done, it waits. */
		volatile LodGenerator.@Nullable Job job;

		Request(LodWorld world, int level, int nx, int nz) {
			this.world = world;
			this.level = level;
			this.nx = nx;
			this.nz = nz;
		}
	}

	record Result(Request request, LodMesher.Mesh mesh) {
	}

	private final ConcurrentLinkedQueue<LodSnapshot> snapshots = new ConcurrentLinkedQueue<>();
	private final AtomicLong snapshotBytes = new AtomicLong();
	final ConcurrentHashMap<Long, Request> requests = new ConcurrentHashMap<>();
	final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
	/** Land made from the seed, to put in. */
	private final ConcurrentLinkedQueue<LodGenerator.Made> made = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
	private final Set<LodWorld> open = Collections.newSetFromMap(new IdentityHashMap<>());
	private final LodMesher mesher = new LodMesher();
	private final Thread thread;
	private volatile boolean stopping;
	private long lastSave = System.nanoTime();
	private int errors;
	private long statChunks, statSheetNanos, statMipNanos, statNodes, statQuads, statNodeNanos, statMade, statMadeNanos;
	/** With STATS: the last chunk copies and node requests, done again when the game closes ({@link #timing}). */
	private final ArrayDeque<LodSnapshot> statSnapshots = new ArrayDeque<>();
	private final ArrayDeque<Request> statRequests = new ArrayDeque<>();

	LodWorker() {
		thread = new Thread(this::run, "Afterburner far terrain");
		thread.setDaemon(true);
		// Below the game's chunk builders and world generation (Windows: lowest vs below normal): those come first.
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.start();
	}

	/** Whether there's room for another chunk copy. */
	boolean hasRoom() {
		return !stopping && snapshotBytes.get() < SNAPSHOT_LIMIT;
	}

	void add(LodSnapshot snapshot) {
		snapshotBytes.addAndGet(snapshot.bytes);
		snapshots.add(snapshot);
		LockSupport.unpark(thread);
	}

	/** Saves the world and lets go of it, after the chunk copies already queued for it. */
	void close(LodWorld world) {
		tasks.add(() -> {
			world.closed = true;
			world.save();
			open.remove(world);
		});
		LockSupport.unpark(thread);
	}

	void wake() {
		LockSupport.unpark(thread);
	}

	/** Any thread: land made, to put in. */
	void made(LodGenerator.Made m) {
		made.add(m);
		LockSupport.unpark(thread);
	}

	/** Finishes what's queued and saves, waiting at most {@code millis}. */
	void stop(long millis) {
		stopping = true;
		LockSupport.unpark(thread);
		try {
			thread.join(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void run() {
		while (true) {
			boolean busy = false;
			try {
				if (stopping) {
					finish();
					return;
				}
				// The copies first, oldest first: a task (closing a world) only runs once those queued before it are in.
				for (int i = 0; i < 8; i++) {
					LodSnapshot s = snapshots.poll();
					if (s == null) break;
					ingest(s);
					busy = true;
				}
				for (int i = 0; i < 8; i++) {
					LodGenerator.Made m = made.poll();
					if (m == null) break;
					ingest(m);
					busy = true;
				}
				if (snapshots.isEmpty()) {
					Runnable task;
					while ((task = tasks.poll()) != null) task.run();
				}
				Request r = take();
				if (r != null) {
					busy |= build(r);
				}
				long now = System.nanoTime();
				if (now - lastSave > SAVE_EVERY) {
					lastSave = now;
					for (LodWorld w : open) {
						w.save();
						w.trim();
					}
					if (STATS) logStats();
				}
			} catch (Throwable t) {
				if (errors++ < 10) LOGGER.error("[Afterburner] Far terrain worker error", t);
			}
			if (!busy) LockSupport.parkNanos(20_000_000L);
		}
	}

	private void finish() {
		LodSnapshot s;
		while ((s = snapshots.poll()) != null) {
			try {
				ingest(s);
			} catch (Throwable t) {
				if (errors++ < 10) LOGGER.error("[Afterburner] Far terrain worker error", t);
			}
		}
		Runnable task;
		while ((task = tasks.poll()) != null) task.run();
		for (LodWorld w : open) w.save();
		if (STATS) {
			logStats();
			timing();
		}
		open.clear();
	}

	private void logStats() {
		long madeSheets = LodGenerator.MADE.get();
		LOGGER.info("[Afterburner] Far terrain stats: {} chunks, {} us to convert, {} us for the levels above; {} nodes, {} quads, {} us to build; {} sheets of land made, {} us each, {} us to put in",
				statChunks, statChunks == 0 ? 0 : statSheetNanos / statChunks / 1000, statChunks == 0 ? 0 : statMipNanos / statChunks / 1000,
				statNodes, statNodes == 0 ? 0 : statQuads / statNodes, statNodes == 0 ? 0 : statNodeNanos / statNodes / 1000,
				madeSheets, madeSheets == 0 ? 0 : LodGenerator.MADE_NANOS.get() / madeSheets / 1000, statMade == 0 ? 0 : statMadeNanos / statMade / 1000);
	}

	/**
	 * When the game closes, after saving: converts the last chunks and builds the last nodes again, best of 5 rounds, so two
	 * builds of the code can be compared without the rest of the game in the way.
	 */
	private void timing() {
		if (statSnapshots.isEmpty() || statRequests.isEmpty()) return;
		int differ = 0;
		for (LodSnapshot s : statSnapshots) {
			LodSheet a = s.toSheet();
			LodSnapshot.airShortcut = false;
			LodSheet b = s.toSheet();
			LodSnapshot.airShortcut = true;
			if (!Arrays.deepEquals(a.columns, b.columns) || !Arrays.equals(a.biomes, b.biomes)) differ++;
		}
		long sheetBest = Long.MAX_VALUE, nodeBest = Long.MAX_VALUE;
		for (int round = 0; round < 5; round++) {
			long t0 = System.nanoTime();
			for (LodSnapshot s : statSnapshots) s.toSheet();
			long t1 = System.nanoTime();
			for (Request r : statRequests) {
				LodMesher.Mesh mesh = mesher.build(r.world, r.level, r.nx, r.nz, 0);
				if (mesh != null) mesh.free();
			}
			long t2 = System.nanoTime();
			sheetBest = Math.min(sheetBest, t1 - t0);
			nodeBest = Math.min(nodeBest, t2 - t1);
		}
		LOGGER.info("[Afterburner] Far terrain timing (best of 5): {} us per chunk ({}), {} us per node ({}); air shortcut differs in {} chunks",
				sheetBest / statSnapshots.size() / 1000, statSnapshots.size(), nodeBest / statRequests.size() / 1000, statRequests.size(), differ);
	}

	/** The chunk's sheet, and the levels above it as far as they change. */
	private void ingest(LodSnapshot s) {
		snapshotBytes.addAndGet(-s.bytes);
		LodWorld world = s.world;
		open.add(world);
		long t0 = STATS ? System.nanoTime() : 0;
		LodSheet sheet = s.toSheet();
		if (sheet.lightMissing()) return;
		if (STATS) {
			long t1 = System.nanoTime();
			statChunks++;
			statSheetNanos += t1 - t0;
			t0 = t1;
		}
		mips(world, sheet, 0, s.cx, s.cz);
		if (STATS) {
			statMipNanos += System.nanoTime() - t0;
			statSnapshots.addLast(s);
			if (statSnapshots.size() > 64) statSnapshots.removeFirst();
		}
	}

	/**
	 * Land made from the seed: put in where nothing was seen (in a sheet partly seen, only the columns not), as the world's
	 * own palette places and biomes. The job's end lets its node be built.
	 */
	private void ingest(LodGenerator.Made m) {
		LodGenerator.Job job = m.job();
		FarLand.Sheet land = m.sheet();
		if (land == null) {
			job.done = true;
			return;
		}
		LodWorld world = job.world;
		if (world.closed) return;
		long t0 = STATS ? System.nanoTime() : 0;
		open.add(world);
		LodSheet old = world.get(job.level, m.sx(), m.sz());
		LodSheet sheet = new LodSheet();
		if (old != null) {
			System.arraycopy(old.columns, 0, sheet.columns, 0, LodSheet.COLUMNS);
			System.arraycopy(old.biomes, 0, sheet.biomes, 0, LodSheet.COLUMNS);
		}
		int[] ids = new int[land.palette.size()];
		for (int i = 0; i < ids.length; i++) {
			BlockState state = land.palette.get(i);
			int id = i == FarLand.AIR || state == null ? LodSheet.AIR : i == FarLand.HIDDEN ? LodSheet.HIDDEN : world.stateId(state);
			ids[i] = world.kind(id) == LodKinds.EMPTY ? LodSheet.AIR : id;
		}
		boolean any = false;
		for (int c = 0; c < LodSheet.COLUMNS; c++) {
			if (sheet.columns[c] != null) continue;
			long[] runs = land.columns[c];
			long[] out = new long[runs.length];
			for (int r = 0; r < runs.length; r++) {
				int what = LodSheet.what(runs[r]);
				out[r] = LodSheet.run(ids[LodSheet.state(what)] << 8 | what & 0xFF, LodSheet.top(runs[r]));
			}
			sheet.columns[c] = out;
			sheet.biomes[c] = world.biomeId(name(land.biomes[c])) | FarLand.VERSION << LodSheet.MADE_SHIFT;
			any = true;
		}
		if (any) mips(world, sheet, job.level, m.sx(), m.sz());
		if (STATS) {
			statMade++;
			statMadeNanos += System.nanoTime() - t0;
		}
	}

	private static final Identifier PLAINS = Identifier.withDefaultNamespace("plains");

	private static Identifier name(@Nullable Holder<Biome> biome) {
		return biome == null ? PLAINS : biome.unwrapKey().map(k -> k.identifier()).orElse(PLAINS);
	}

	/** Puts the sheet in at its level, and works out the levels above it as far as they change. */
	private static void mips(LodWorld world, LodSheet sheet, int level, int sx, int sz) {
		if (!world.put(level, sx, sz, sheet)) return;
		for (; level < LodWorld.MAX_LEVEL; level++) {
			int px = sx >> 1, pz = sz >> 1;
			LodSheet parent = LodMip.quadrant(world, level, sheet, world.get(level + 1, px, pz), sx & 1, sz & 1);
			if (!world.put(level + 1, px, pz, parent)) return;
			sheet = parent;
			sx = px;
			sz = pz;
		}
	}

	/** The waiting request nearest the player, claimed (not one waiting for land to be made). */
	private Request take() {
		Request best = null;
		for (Request r : requests.values()) {
			if (r.taken.get()) continue;
			LodGenerator.Job job = r.job;
			if (job != null && !job.done) continue;
			if (best == null || r.priority < best.priority) best = r;
		}
		if (best == null || !best.taken.compareAndSet(false, true)) return null;
		return best;
	}

	private boolean build(Request r) {
		LodWorld world = r.world;
		LodGenerator generator = world.generator;
		if (generator != null && r.job == null && generator.usable()) {
			int[] missing = missing(world, r.level, r.nx, r.nz);
			if (missing.length > 0) {
				// Built once the land's made (take() passes it over until then).
				generator.submit(r, missing);
				r.taken.set(false);
				return true;
			}
		}
		int version = world.version(r.level, r.nx, r.nz);
		long t0 = STATS ? System.nanoTime() : 0;
		LodMesher.Mesh mesh = mesher.build(world, r.level, r.nx, r.nz, version);
		if (STATS && mesh != null) {
			statNodes++;
			statQuads += mesh.quads();
			statNodeNanos += System.nanoTime() - t0;
			statRequests.addLast(r);
			if (statRequests.size() > 16) statRequests.removeFirst();
		}
		// The result goes out before the request goes away, so the renderer never sees neither and asks again.
		if (mesh != null) results.add(new Result(r, mesh));
		requests.remove(LodWorld.key(r.level, r.nx, r.nz), r);
		return mesh != null;
	}

	/** The node's sheets with columns missing, by place in it ({@code dz * 8 + dx}). */
	private static int[] missing(LodWorld world, int level, int nx, int nz) {
		int n = LodMesher.SHEETS;
		int[] out = new int[n * n];
		int count = 0;
		for (int i = 0; i < n * n; i++) {
			LodSheet sheet = world.get(level, nx * n + (i & 7), nz * n + (i >> 3));
			if (sheet == null || !sheet.isFull()) out[count++] = i;
		}
		return Arrays.copyOf(out, count);
	}
}
