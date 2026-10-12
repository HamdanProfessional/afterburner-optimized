package com.afterburner.worldgen;

import com.afterburner.Afterburner;

import java.util.concurrent.atomic.AtomicLong;

/** Check for ore veins sharing the noise they work out for the whole chunk. */
public final class OreVeinShare {
	/** {@code -Dafterburner.checkOres=true}: a shared noise is also worked out again and every block's number compared. */
	public static final boolean CHECK = Boolean.getBoolean("afterburner.checkOres");
	private static final AtomicLong SHARED = new AtomicLong(), CHECKED = new AtomicLong(), DIFFERENT = new AtomicLong();

	private OreVeinShare() {
	}

	public static void shared() {
		SHARED.incrementAndGet();
	}

	public static float compare(float shared, float vanilla) {
		CHECKED.incrementAndGet();
		if (Float.floatToIntBits(shared) != Float.floatToIntBits(vanilla) && DIFFERENT.incrementAndGet() <= 5) {
			Afterburner.LOGGER.warn("Ore vein check: shared noise gave {}, vanilla {}", shared, vanilla);
		}
		return shared;
	}

	public static String summary() {
		return "ore vein check: " + String.format("%,d", SHARED.get()) + " noises shared, " + String.format("%,d", CHECKED.get())
				+ " blocks compared, " + DIFFERENT.get() + " different";
	}
}
