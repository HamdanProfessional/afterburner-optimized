package com.afterburner.collision;

import com.afterburner.Afterburner;

import java.util.concurrent.atomic.AtomicLong;

/**
 * For testing: with {@code -Dafterburner.checkCollisions=true}, every collision lookup is also done the vanilla way and
 * the two answers are compared. Slow, only for making sure the faster code gives the same answers.
 */
public final class CollisionCheck {
	public static final boolean ENABLED = Boolean.getBoolean("afterburner.checkCollisions");
	private static final AtomicLong CHECKED = new AtomicLong();
	private static final AtomicLong WRONG = new AtomicLong();

	private CollisionCheck() {
	}

	public static void result(String what, boolean same, Object details) {
		CHECKED.incrementAndGet();
		if (!same && WRONG.incrementAndGet() <= 20) {
			Afterburner.LOGGER.error("Collision check failed ({}): {}", what, details);
		}
	}

	/** For benchmark reports, e.g. "collision check: 1,234,567 compared, 0 different". */
	public static String summary() {
		return "collision check: " + String.format("%,d", CHECKED.get()) + " compared, " + WRONG.get() + " different";
	}
}
