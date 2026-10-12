package com.afterburner.client.render;

import com.afterburner.Afterburner;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Remembers each block position's biome while one chunk section is being built. Water, grass and leaf colours blend
 * the biomes around each block (5 x 5 of them at the default biome blend), so neighbouring blocks ask for mostly the
 * same positions: vanilla looks every one of them up again, which costs a chunk lookup and the biome "zoom" each time.
 * Kept: the section's own 16 block layers, reaching 7 blocks past it sideways (the largest blend). Anything else is
 * looked up the vanilla way. Same biomes, so the same colours.
 */
public final class BiomeMemo {
	/** For testing: with {@code -Dafterburner.checkTint=true}, every remembered biome is also looked up again and compared. */
	public static final boolean CHECK = Boolean.getBoolean("afterburner.checkTint");
	private static final int REACH = 7;
	private static final int SIZE = 16 + 2 * REACH;
	private static final ThreadLocal<BiomeMemo> MEMO = ThreadLocal.withInitial(BiomeMemo::new);
	private static final Queue<BiomeMemo> ALL = new ConcurrentLinkedQueue<>();

	@SuppressWarnings("unchecked")
	private final @Nullable Holder<Biome>[] biomes = new Holder[SIZE * SIZE * 16];
	private final int[] stamps = new int[SIZE * SIZE * 16];
	private int stamp;
	private boolean building;
	private int minX, minY, minZ;
	/** Per builder thread, summed for benchmark reports (not exact while builds run). */
	private long lookups, remembered, checked, wrong;

	private BiomeMemo() {
		ALL.add(this);
	}

	/** A section build starts on this thread: nothing remembered from before counts. */
	public static void begin(SectionPos section) {
		BiomeMemo memo = MEMO.get();
		if (++memo.stamp == 0) {
			Arrays.fill(memo.stamps, 0);
			memo.stamp = 1;
		}
		memo.minX = section.minBlockX() - REACH;
		memo.minY = section.minBlockY();
		memo.minZ = section.minBlockZ() - REACH;
		memo.building = true;
	}

	public static void end() {
		MEMO.get().building = false;
	}

	/** The biome at {@code pos}: remembered while a section is being built on this thread, else {@code original}. */
	public static Holder<Biome> biome(ClientLevel level, BlockPos pos, Operation<Holder<Biome>> original) {
		BiomeMemo memo = MEMO.get();
		if (!memo.building) return original.call(level, pos);
		int x = pos.getX() - memo.minX, y = pos.getY() - memo.minY, z = pos.getZ() - memo.minZ;
		if (x < 0 || x >= SIZE || y < 0 || y >= 16 || z < 0 || z >= SIZE) return original.call(level, pos);
		int i = (y * SIZE + z) * SIZE + x;
		memo.lookups++;
		if (memo.stamps[i] == memo.stamp) {
			memo.remembered++;
			Holder<Biome> biome = memo.biomes[i];
			if (CHECK) memo.check(biome, original.call(level, pos), pos);
			return biome;
		}
		Holder<Biome> biome = original.call(level, pos);
		memo.biomes[i] = biome;
		memo.stamps[i] = memo.stamp;
		return biome;
	}

	private void check(Holder<Biome> remembered, Holder<Biome> now, BlockPos pos) {
		checked++;
		if (remembered != now && ++wrong <= 20) {
			Afterburner.LOGGER.error("Biome blend check failed at {}: remembered {}, now {}", pos.immutable(), remembered, now);
		}
	}

	public static void reset() {
		for (BiomeMemo memo : ALL) memo.lookups = memo.remembered = memo.checked = memo.wrong = 0;
	}

	/** For benchmark reports, e.g. "Biome blend: 1,234,567 biome lookups, 94.0% remembered". */
	public static String summary() {
		long lookups = 0, remembered = 0, checked = 0, wrong = 0;
		for (BiomeMemo memo : ALL) {
			lookups += memo.lookups;
			remembered += memo.remembered;
			checked += memo.checked;
			wrong += memo.wrong;
		}
		String line = "Biome blend: " + String.format("%,d", lookups) + " biome lookups while building, "
				+ String.format("%.1f", lookups == 0 ? 0.0 : 100.0 * remembered / lookups) + "% remembered";
		if (CHECK) line += "; check: " + String.format("%,d", checked) + " compared, " + wrong + " different";
		return line;
	}
}
