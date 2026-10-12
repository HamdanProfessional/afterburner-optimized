package com.afterburner.tick;

import java.util.function.LongConsumer;

/** Added to the parts of the game that tell {@link TickWaker} when a chunk may be able to run ticks now. */
public interface ChunkListener {
	void afterburner$listen(LongConsumer listener);
}
