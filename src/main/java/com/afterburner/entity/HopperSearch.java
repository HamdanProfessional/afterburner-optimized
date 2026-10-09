package com.afterburner.entity;

import com.afterburner.Afterburner;
import com.afterburner.mixin.collision.LevelEntitiesAccessor;
import com.afterburner.mixin.collision.LevelEntityGetterAdapterAccessor;
import net.minecraft.util.Continuation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * A hopper with no chest or other container block above it (or in front of it) looks for a minecart with a chest or
 * hopper there instead, every tick it's not cooling down. Vanilla looks at every entity near the spot for that, and under a
 * pen of chickens that's every chicken. Each entity section counts the entities in it that hold items, and the search
 * skips the sections with none; in the others it looks at the same entities in the same order, so it finds the same.
 */
public final class HopperSearch {
	/** {@code -Dafterburner.checkHoppers=true}: each search is also done vanilla's way and the two compared. */
	public static final boolean CHECK = Boolean.getBoolean("afterburner.checkHoppers");
	private static final AtomicLong CHECKED = new AtomicLong(), DIFFERENT = new AtomicLong();

	private HopperSearch() {
	}

	/** Added to entity sections: how many entities in it hold items ({@link Container}). */
	public interface Count {
		int afterburner$containers();
	}

	/** {@code level.getEntities(null, box, CONTAINER_ENTITY_SELECTOR)}, or null to do it vanilla's way. */
	@SuppressWarnings("unchecked")
	public static @Nullable List<Entity> containers(Level level, AABB box, Predicate<? super Entity> selector) {
		LevelEntityGetter<Entity> entities = ((LevelEntitiesAccessor) level).afterburner$entities();
		if (selector != EntitySelector.CONTAINER_ENTITY_SELECTOR || !(entities instanceof LevelEntityGetterAdapter<Entity> adapter)) return null;
		List<Entity> found = new ArrayList<>();
		((LevelEntityGetterAdapterAccessor<Entity>) adapter).afterburner$sections().forEachAccessibleNonEmptySection(box, section ->
				((Count) section).afterburner$containers() == 0 ? Continuation.CONTINUE : section.getEntities(box, entity -> {
					if (selector.test(entity)) found.add(entity);
					return Continuation.CONTINUE;
				}));
		// Vanilla then adds the dragon's parts that pass the selector; they don't hold items, so none do.
		return found;
	}

	/** Check mode: the found list next to vanilla's. */
	public static void check(List<Entity> found, List<Entity> vanilla) {
		CHECKED.incrementAndGet();
		if (!found.equals(vanilla) && DIFFERENT.incrementAndGet() <= 20) {
			Afterburner.LOGGER.error("Hopper check failed: found {}, vanilla {}", found, vanilla);
		}
	}

	public static boolean holdsItems(Object entity) {
		return entity instanceof Container;
	}

	/** For benchmark reports, e.g. "hopper check: 1,234 compared, 0 different". */
	public static String summary() {
		return "hopper check: " + String.format("%,d", CHECKED.get()) + " compared, " + DIFFERENT.get() + " different";
	}
}
