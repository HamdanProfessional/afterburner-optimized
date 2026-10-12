package com.afterburner.tick;

/**
 * Added to the game's scheduled tick lists ({@code LevelTicks}). Every tick vanilla looks at every chunk that has a block
 * or fluid tick due, and asks again for each one that can't run it (outside simulation distance, or its mobs aren't
 * loaded yet). Those chunks are set aside instead, and put back when something that decides it changes for that chunk
 * ({@link TickWaker}). The same ticks run on the same tick as in vanilla.
 */
public interface ParkedTicks {
	/** Turns setting aside on. Only for a world's own lists: changes to those chunks are reported to {@link #afterburner$wake}. */
	void afterburner$attach();

	/** Whether the chunk can run ticks may have changed: if it was set aside, it's looked at again next tick. */
	void afterburner$wake(long chunk);

	/** How many chunks are set aside now. */
	int afterburner$parked();
}
