package com.afterburner.tick;

import com.afterburner.Afterburner;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Random block ticks: every tick, vanilla looks at each of a chunk's sections (24 in the overworld, 1,089 chunks at
 * simulation distance 16) to ask whether anything in it random-ticks, and only a few do. Each chunk on the server keeps a
 * second array of its sections in which those with nothing that random-ticks are replaced by one empty section per world,
 * kept up to date whenever a section's counts change. The random tick loop walks that array instead: the same sections
 * tick in the same order, without each quiet one being read from memory.
 * <p>
 * The same feature also has the check whether a dimension has to keep running look where it found its reason last tick
 * first, instead of walking every chunk ticket (see {@code TicketStorageMixin}).
 */
public final class TickingSections {
	/** {@code -Dafterburner.checkChunkTicks=true}: each chunk's array is compared with its real sections every tick, and the dimension check with the full walk. */
	public static final boolean CHECK = Boolean.getBoolean("afterburner.checkChunkTicks");
	private static final AtomicLong CHECKED = new AtomicLong(), DIFFERENT = new AtomicLong();
	private static final AtomicLong KEEP_CHECKED = new AtomicLong(), KEEP_DIFFERENT = new AtomicLong();

	private TickingSections() {
	}

	/** On a server chunk: its sections with the quiet ones replaced, or null if it has none (a client chunk). */
	public interface Owner {
		LevelChunkSection @Nullable [] afterburner$tickingSections();
	}

	/** On a section: the array it keeps itself up to date in. */
	public interface Owned {
		void afterburner$own(LevelChunkSection[] ticking, int index, LevelChunkSection quiet);
	}

	/** On a server world: its one empty section that stands in for the quiet ones. */
	public interface Quiet {
		LevelChunkSection afterburner$quietSection();
	}

	/** The array for vanilla's random tick loop: the chunk's own one, or its real sections if it has none. */
	public static LevelChunkSection[] pick(LevelChunkSection[] sections, LevelChunk chunk) {
		LevelChunkSection[] ticking = ((Owner) chunk).afterburner$tickingSections();
		if (ticking == null || ticking.length != sections.length) return sections;
		if (CHECK) check(sections, ticking, chunk);
		return ticking;
	}

	private static void check(LevelChunkSection[] sections, LevelChunkSection[] ticking, LevelChunk chunk) {
		CHECKED.incrementAndGet();
		for (int i = 0; i < sections.length; i++) {
			boolean ticks = sections[i].isRandomlyTicking();
			if (ticks ? ticking[i] != sections[i] : ticking[i].isRandomlyTicking()) {
				if (DIFFERENT.incrementAndGet() <= 20) {
					Afterburner.LOGGER.error("Chunk tick check failed: chunk {} section {} ticks {}, array has {}", chunk.getPos(), i, ticks,
							ticking[i] == sections[i] ? "it" : "the quiet one");
				}
				return;
			}
		}
	}

	/** Check mode: the dimension check's answer next to the full walk's. */
	public static void checkKeepActive(boolean ours, boolean vanilla) {
		KEEP_CHECKED.incrementAndGet();
		if (ours != vanilla && KEEP_DIFFERENT.incrementAndGet() <= 20) {
			Afterburner.LOGGER.error("Chunk tick check failed: dimension kept active {}, vanilla {}", ours, vanilla);
		}
	}

	/** For benchmark reports, e.g. "chunk tick check: 1,234 chunks compared, 0 different; dimension 40 compared, 0 different". */
	public static String summary() {
		return "chunk tick check: " + String.format("%,d", CHECKED.get()) + " chunks compared, " + DIFFERENT.get() + " different; dimension "
				+ String.format("%,d", KEEP_CHECKED.get()) + " compared, " + KEEP_DIFFERENT.get() + " different";
	}
}
