package com.afterburner.ai;

import com.afterburner.Afterburner;

import java.util.concurrent.atomic.AtomicLong;

/**
 * For testing: with {@code -Dafterburner.checkMobAi=true}, every faster mob AI lookup is also done the vanilla way and
 * the two answers are compared. Slow, only for making sure the faster code gives the same answers.
 */
public final class AiCheck {
	public static final boolean ENABLED = Boolean.getBoolean("afterburner.checkMobAi");
	private static final AtomicLong CHECKED = new AtomicLong();
	private static final AtomicLong WRONG = new AtomicLong();

	private AiCheck() {
	}

	public static void result(String what, boolean same, Object details) {
		CHECKED.incrementAndGet();
		if (!same && WRONG.incrementAndGet() <= 20) {
			Afterburner.LOGGER.error("Mob AI check failed ({}): {}", what, details);
		}
	}

	/** For benchmark reports, e.g. "mob AI check: 1,234 compared, 0 different". */
	public static String summary() {
		return "mob AI check: " + String.format("%,d", CHECKED.get()) + " compared, " + WRONG.get() + " different";
	}
}
