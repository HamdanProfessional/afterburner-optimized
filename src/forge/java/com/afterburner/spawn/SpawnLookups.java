package com.afterburner.spawn;

import com.afterburner.Afterburner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.MobSpawnSettings;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Each mob group vanilla tries to spawn starts by picking a mob from the list of mobs that may spawn at the first spot,
 * and right away asks whether that mob is on the list at that spot: the same structure and biome lookups again, on a
 * world nothing has changed in since, so always yes. The pick is remembered here for that one question.
 */
public final class SpawnLookups {
	/** {@code -Dafterburner.checkSpawning=true}: the question is also asked vanilla's way and the answers compared. */
	public static final boolean CHECK = Boolean.getBoolean("afterburner.checkSpawning");
	private static final AtomicLong CHECKED = new AtomicLong(), DIFFERENT = new AtomicLong();

	private static @Nullable Thread thread;
	private static @Nullable ServerLevel level;
	private static @Nullable MobCategory category;
	private static MobSpawnSettings.@Nullable SpawnerData picked;
	private static long pos;

	private SpawnLookups() {
	}

	/** A mob was just picked from the list at {@code at}. */
	public static void picked(ServerLevel level, MobCategory category, MobSpawnSettings.SpawnerData picked, BlockPos at) {
		SpawnLookups.thread = Thread.currentThread();
		SpawnLookups.level = level;
		SpawnLookups.category = category;
		SpawnLookups.picked = picked;
		SpawnLookups.pos = at.asLong();
	}

	/** Whether this is the question right after the pick, for the same mob at the same spot. Answers once. */
	public static boolean justPicked(ServerLevel level, MobCategory category, MobSpawnSettings.SpawnerData data, BlockPos at) {
		boolean same = picked == data && thread == Thread.currentThread() && SpawnLookups.level == level
				&& SpawnLookups.category == category && pos == at.asLong();
		forget();
		return same;
	}

	/** Drops the pick (and the level with it, so a closed world isn't kept). */
	public static void forget() {
		thread = null;
		level = null;
		category = null;
		picked = null;
	}

	/** Check mode: vanilla's answer where the pick says yes. */
	public static void check(boolean vanilla) {
		CHECKED.incrementAndGet();
		if (!vanilla && DIFFERENT.incrementAndGet() <= 20) {
			Afterburner.LOGGER.error("Spawn check failed: vanilla says the mob just picked may not spawn at its spot");
		}
	}

	/** Check mode: whether vanilla's lookup found no spawn costs at a spot where {@link SpawnCosts} skipped it. */
	public static void checkCost(boolean none) {
		CHECKED.incrementAndGet();
		if (!none && DIFFERENT.incrementAndGet() <= 20) {
			Afterburner.LOGGER.error("Spawn check failed: vanilla finds a spawn cost where it was skipped as none");
		}
	}

	/** For benchmark reports, e.g. "spawn check: 1,234 compared, 0 different". */
	public static String summary() {
		return "spawn check: " + String.format("%,d", CHECKED.get()) + " compared, " + DIFFERENT.get() + " different";
	}
}
