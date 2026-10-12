package com.afterburner.tick;

import com.afterburner.Afterburner;
import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts for {@link ParkedTicks}, and its self-check: with {@code -Dafterburner.checkTicks=true}, every tick each chunk
 * set aside with ticks due is asked again, as vanilla does. One that could run them is a mistake (it's logged, and run
 * anyway as vanilla would). Slow, only for making sure setting aside gives the same results.
 */
public final class TickParking {
	public static final boolean CHECK = Boolean.getBoolean("afterburner.checkTicks");
	/** Chunks set aside and put back since the counts were reset (server thread only). */
	public static long parked, woken;
	private static final AtomicLong CHECKED = new AtomicLong();
	private static final AtomicLong WRONG = new AtomicLong();

	private TickParking() {
	}

	public static void checked(long chunk, boolean couldRun) {
		CHECKED.incrementAndGet();
		if (couldRun && WRONG.incrementAndGet() <= 20) {
			Afterburner.LOGGER.error("Scheduled tick check failed: chunk {} was set aside but can run its ticks", ChunkPos.unpack(chunk));
		}
	}

	public static void reset() {
		parked = woken = 0;
	}

	/** For benchmark reports, e.g. "Scheduled ticks: 1,234 chunks set aside, 1,100 put back". */
	public static String summary() {
		String s = "Scheduled ticks: " + String.format("%,d", parked) + " chunks set aside, " + String.format("%,d", woken) + " put back";
		if (CHECK) s += "; check: " + String.format("%,d", CHECKED.get()) + " looked at, " + WRONG.get() + " could have run";
		return s;
	}
}
