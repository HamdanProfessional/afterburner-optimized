package com.afterburner.client.render;

import net.minecraft.core.SectionPos;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Smooth lighting reads the light and shade of the blocks around every block face. Vanilla keeps the last 100 of them
 * while a chunk section is built, but it goes through the section row by row, so the block above comes up again 16
 * blocks later and the one behind 256 later, long forgotten. This keeps all of them for the section and one block
 * around it, for the length of the build. Same values, so the same lighting.
 * See {@link com.afterburner.client.mixin.render.LightMemoMixin}.
 */
public interface LightMemo {
	/** For testing: with {@code -Dafterburner.checkLight=true}, every remembered light value is also read again and compared. */
	boolean CHECK = Boolean.getBoolean("afterburner.checkLight");
	/** Every builder thread's memo, for benchmark reports. */
	Queue<LightMemo> ALL = new ConcurrentLinkedQueue<>();

	/** A section build starts on this thread: positions are this section's, and nothing remembered from before counts. */
	void afterburner$anchor(SectionPos section);

	/** Lookups, remembered, checked, different (not exact while builds run). */
	long[] afterburner$stats();

	void afterburner$resetStats();

	static void reset() {
		for (LightMemo memo : ALL) memo.afterburner$resetStats();
	}

	/** For benchmark reports, e.g. "Smooth lighting: 12,345,678 light lookups while building, 91.0% remembered". */
	static String summary() {
		long lookups = 0, remembered = 0, checked = 0, wrong = 0;
		for (LightMemo memo : ALL) {
			long[] s = memo.afterburner$stats();
			lookups += s[0];
			remembered += s[1];
			checked += s[2];
			wrong += s[3];
		}
		String line = "Smooth lighting: " + String.format("%,d", lookups) + " light lookups while building, "
				+ String.format("%.1f", lookups == 0 ? 0.0 : 100.0 * remembered / lookups) + "% remembered";
		if (CHECK) line += "; check: " + String.format("%,d", checked) + " compared, " + wrong + " different";
		return line;
	}
}
