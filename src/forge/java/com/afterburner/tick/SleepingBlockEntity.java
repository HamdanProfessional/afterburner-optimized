package com.afterburner.tick;

import com.afterburner.Afterburner;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Added to block entities. A furnace, smoker, blast furnace or campfire whose tick found nothing to do next time (no fuel
 * burning, nothing to cook, nothing cooling down) sleeps: its ticks are skipped, as they'd change nothing, until something
 * that could give it work wakes it (an item put in, its block changing, being loaded or given components, anything marking
 * it changed).
 * <p>
 * With {@code -Dafterburner.checkSleep=true} every skipped tick first makes sure there's still nothing to do. One with work
 * is a mistake (it's logged, and woken to do it).
 */
public interface SleepingBlockEntity {
	boolean CHECK = Boolean.getBoolean("afterburner.checkSleep");
	AtomicLong WRONG = new AtomicLong();

	boolean afterburner$asleep();

	void afterburner$setAsleep(boolean asleep);

	/** Whether its next tick would still do nothing (for the check). */
	boolean afterburner$stillIdle();

	/** Only the game's own ones sleep: another mod's furnace may do more in its tick. */
	static boolean sleeps(BlockEntity entity) {
		Class<?> c = entity.getClass();
		return c == FurnaceBlockEntity.class || c == BlastFurnaceBlockEntity.class || c == SmokerBlockEntity.class || c == CampfireBlockEntity.class;
	}

	static void wrong(BlockEntity entity) {
		if (WRONG.incrementAndGet() <= 20) {
			Afterburner.LOGGER.error("Sleep check failed: {} @ {} slept with work to do", entity.typeHolder().getRegisteredName(), entity.getBlockPos());
		}
	}
}
