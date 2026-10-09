package com.afterburner.tick;

/** Added to the server's worlds: passes {@link ParkedTicks#afterburner$wake} on to the world's block and fluid tick lists. */
public interface TickWaker {
	void afterburner$wakeTicks(long chunk);
}
