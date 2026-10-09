package com.afterburner.entity;

import com.afterburner.Afterburner;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.Continuation;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Finds the entity sections a box reaches, for every entity search (mobs pushing each other, items and hoppers, collisions,
 * targeting). For each column of sections along x the box reaches, vanilla walks every section the world has at that x (all
 * along z, and up and down, often a hundred or more) and keeps those in the box. This looks the box's few sections up
 * directly instead, in the order vanilla's walk gives them, so the same sections come in the same order.
 * <p>
 * Vanilla walks its sorted set of section keys. A key holds x, z and y in that order, each as an unsigned bit field, so at
 * one x the keys come by z from 0 up, then the negative z from the most negative up; within one z the same by y. A box with
 * more places at one x than a column is likely to hold is walked the vanilla way.
 */
public final class SectionSearch {
	/** {@code -Dafterburner.checkEntitySearch=true}: every search is also done the vanilla way and the two compared. Slow. */
	public static final boolean CHECK = Boolean.getBoolean("afterburner.checkEntitySearch");
	/** Places (z by y) at one x looked up directly at most; more, and the vanilla walk is likely cheaper. */
	private static final int MAX_DIRECT = 32;
	/** The range a packed key holds for each (x one less: vanilla's end of the walk at the top x would wrap around). */
	private static final int MIN_XZ = -(1 << 21), MAX_X = (1 << 21) - 2, MAX_Z = (1 << 21) - 1, MIN_Y = -(1 << 19), MAX_Y = (1 << 19) - 1;
	private static final AtomicLong CHECKED = new AtomicLong(), DIFFERENT = new AtomicLong();

	private SectionSearch() {
	}

	/** Vanilla's {@code EntitySectionStorage.forEachAccessibleNonEmptySection}, with the same sections in the same order. */
	public static <T extends EntityAccess> void forEach(Long2ObjectMap<EntitySection<T>> sections, LongSortedSet ids, AABB bb,
			AbortableIterationConsumer<EntitySection<T>> output) {
		int xMin = SectionPos.posToSectionCoord(bb.minX - 2.0);
		int yMin = SectionPos.posToSectionCoord(bb.minY - 4.0);
		int zMin = SectionPos.posToSectionCoord(bb.minZ - 2.0);
		int xMax = SectionPos.posToSectionCoord(bb.maxX + 2.0);
		int yMax = SectionPos.posToSectionCoord(bb.maxY + 0.0);
		int zMax = SectionPos.posToSectionCoord(bb.maxZ + 2.0);
		boolean direct = (long) zMax - zMin + 1 > 0 && ((long) zMax - zMin + 1) * ((long) yMax - yMin + 1) <= MAX_DIRECT
				&& zMin >= MIN_XZ && zMax <= MAX_Z && yMin >= MIN_Y && yMax <= MAX_Y;
		if (CHECK) {
			List<EntitySection<T>> found = new ArrayList<>(), expected = new ArrayList<>();
			for (int x = xMin; x <= xMax; x++) {
				if (direct && x >= MIN_XZ && x <= MAX_X) look(sections, x, yMin, yMax, zMin, zMax, s -> add(found, s));
				else walk(sections, ids, x, yMin, yMax, zMin, zMax, s -> add(found, s));
				walk(sections, ids, x, yMin, yMax, zMin, zMax, s -> add(expected, s));
			}
			CHECKED.incrementAndGet();
			if (!found.equals(expected) && DIFFERENT.incrementAndGet() <= 20) {
				Afterburner.LOGGER.error("Entity search check failed: {} sections, vanilla {}, box {}", found.size(), expected.size(), bb);
			}
			for (EntitySection<T> section : found) {
				if (output.accept(section).shouldAbort()) return;
			}
			return;
		}
		for (int x = xMin; x <= xMax; x++) {
			boolean stop = direct && x >= MIN_XZ && x <= MAX_X ? look(sections, x, yMin, yMax, zMin, zMax, output)
					: walk(sections, ids, x, yMin, yMax, zMin, zMax, output);
			if (stop) return;
		}
	}

	private static <T> Continuation add(List<T> list, T item) {
		list.add(item);
		return Continuation.CONTINUE;
	}

	/** The box's sections at one x, looked up one by one in the walk's order. True if the output asked to stop. */
	private static <T extends EntityAccess> boolean look(Long2ObjectMap<EntitySection<T>> sections, int x, int yMin, int yMax, int zMin,
			int zMax, AbortableIterationConsumer<EntitySection<T>> output) {
		// From 0 up first, then the negative ones.
		for (int pass = 0; pass < 2; pass++) {
			int from = pass == 0 ? Math.max(zMin, 0) : zMin, to = pass == 0 ? zMax : Math.min(zMax, -1);
			for (int z = from; z <= to; z++) {
				if (lookColumn(sections, x, z, yMin, yMax, output)) return true;
			}
		}
		return false;
	}

	private static <T extends EntityAccess> boolean lookColumn(Long2ObjectMap<EntitySection<T>> sections, int x, int z, int yMin, int yMax,
			AbortableIterationConsumer<EntitySection<T>> output) {
		for (int pass = 0; pass < 2; pass++) {
			int from = pass == 0 ? Math.max(yMin, 0) : yMin, to = pass == 0 ? yMax : Math.min(yMax, -1);
			for (int y = from; y <= to; y++) {
				EntitySection<T> section = sections.get(SectionPos.asLong(x, y, z));
				if (section != null && !section.isEmpty() && section.getStatus().isAccessible() && output.accept(section).shouldAbort()) return true;
			}
		}
		return false;
	}

	/** Vanilla's walk at one x. True if the output asked to stop. */
	private static <T extends EntityAccess> boolean walk(Long2ObjectMap<EntitySection<T>> sections, LongSortedSet ids, int x, int yMin,
			int yMax, int zMin, int zMax, AbortableIterationConsumer<EntitySection<T>> output) {
		long lowest = SectionPos.asLong(x, 0, 0);
		long highest = SectionPos.asLong(x, -1, -1);
		LongIterator it = ids.subSet(lowest, highest + 1L).iterator();
		while (it.hasNext()) {
			long key = it.nextLong();
			int y = SectionPos.y(key);
			int z = SectionPos.z(key);
			if (y >= yMin && y <= yMax && z >= zMin && z <= zMax) {
				EntitySection<T> section = sections.get(key);
				if (section != null && !section.isEmpty() && section.getStatus().isAccessible() && output.accept(section).shouldAbort()) return true;
			}
		}
		return false;
	}

	/** For benchmark reports, e.g. "entity search check: 1,234 compared, 0 different". */
	public static String summary() {
		return "entity search check: " + String.format("%,d", CHECKED.get()) + " compared, " + DIFFERENT.get() + " different";
	}
}
