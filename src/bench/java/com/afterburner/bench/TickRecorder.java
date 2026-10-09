package com.afterburner.bench;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Times every server tick (from the start of the tick to its end, so the time the server spends idle between ticks
 * isn't counted) while at least one session is recording. Works for dedicated servers and the singleplayer server.
 */
public final class TickRecorder {
	private static final List<Session> SESSIONS = new CopyOnWriteArrayList<>();
	private static long tickStart;

	private TickRecorder() {
	}

	public static void register() {
		ServerTickEvents.START_SERVER_TICK.register(server -> tickStart = System.nanoTime());
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (SESSIONS.isEmpty()) return;
			long now = System.nanoTime();
			for (Session s : SESSIONS) s.add(tickStart, now);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> SESSIONS.clear());
	}

	/** Records for {@code seconds}, then calls {@code onDone} on the server thread. */
	public static Session start(int seconds, Consumer<Session> onDone) {
		return open(System.nanoTime() + seconds * 1_000_000_000L, onDone);
	}

	/** Records until {@link Session#stop()} is called. */
	public static Session start() {
		return open(Long.MAX_VALUE, null);
	}

	private static Session open(long end, Consumer<Session> onDone) {
		Session s = new Session(end, onDone);
		SESSIONS.add(s);
		return s;
	}

	public static boolean recording() {
		return SESSIONS.stream().anyMatch(s -> s.onDone != null);
	}

	public static final class Session {
		private final LongArrayList ticks = new LongArrayList();
		private final long start = System.nanoTime();
		private final long end;
		private final Consumer<Session> onDone;
		private long stoppedAt;
		private long firstTickStart;
		private long lastTickStart;

		private Session(long end, Consumer<Session> onDone) {
			this.end = end;
			this.onDone = onDone;
		}

		private synchronized void add(long tickStart, long now) {
			// Skip the tick the session started in: it's only partly covered, and it ran the command that started it.
			if (stoppedAt != 0 || tickStart < start) return;
			if (firstTickStart == 0) firstTickStart = tickStart;
			lastTickStart = tickStart;
			ticks.add(now - tickStart);
			if (now >= end) {
				stop();
				if (onDone != null) onDone.accept(this);
			}
		}

		/** Stops recording and returns every tick's duration in nanoseconds. */
		public synchronized long[] stop() {
			if (stoppedAt == 0) {
				stoppedAt = System.nanoTime();
				SESSIONS.remove(this);
			}
			return ticks.toLongArray();
		}

		public synchronized long[] ticks() {
			return ticks.toLongArray();
		}

		/** Real time from start to stop, in seconds. */
		public synchronized double seconds() {
			return ((stoppedAt != 0 ? stoppedAt : System.nanoTime()) - start) / 1e9;
		}

		/** Ticks per second, from the gaps between the start of each tick. */
		public synchronized double tps() {
			return ticks.size() < 2 ? 0 : (ticks.size() - 1) / ((lastTickStart - firstTickStart) / 1e9);
		}
	}
}
