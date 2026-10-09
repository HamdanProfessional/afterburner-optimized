package com.afterburner.mixin.tick;

import com.afterburner.tick.ParkedTicks;
import com.afterburner.tick.TickParking;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Queue;
import java.util.function.LongPredicate;

/**
 * Sets aside the chunks with ticks due that can't run them, see {@link ParkedTicks}. A chunk set aside is left out of
 * {@code nextTickForContainer}, the map vanilla walks every tick, and put back with its first tick's time when woken.
 * Vanilla runs due chunks in a fixed order (by tick time, priority and when they were scheduled), not in the map's,
 * so leaving some out changes nothing else.
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin<T> implements ParkedTicks {
	@Shadow
	@Final
	private LongPredicate tickCheck;
	@Shadow
	@Final
	private Long2ObjectMap<LevelChunkTicks<T>> allContainers;
	@Shadow
	@Final
	private Long2LongMap nextTickForContainer;
	@Shadow
	@Final
	private Queue<LevelChunkTicks<T>> containersToTick;

	/** Only a world's own lists set chunks aside: nothing tells other lists (a mod's) when to put them back. */
	@Unique
	private boolean afterburner$attached;
	/** Chunks left out of {@code nextTickForContainer}: ticks due, but they couldn't run them when last asked. */
	@Unique
	private final LongOpenHashSet afterburner$parked = new LongOpenHashSet();
	/** Found during this tick's walk and set aside after it, as the walk can't change the map it walks. */
	@Unique
	private final LongArrayList afterburner$toPark = new LongArrayList();

	@Override
	public void afterburner$attach() {
		afterburner$attached = true;
	}

	@Override
	public int afterburner$parked() {
		return afterburner$parked.size();
	}

	@Override
	public void afterburner$wake(long chunk) {
		if (afterburner$parked.isEmpty() || !afterburner$parked.remove(chunk)) return;
		TickParking.woken++;
		LevelChunkTicks<T> container = allContainers.get(chunk);
		ScheduledTick<T> next = container != null ? container.peek() : null;
		// It may be back already, from a tick scheduled before all the others since.
		if (next != null && !nextTickForContainer.containsKey(chunk)) nextTickForContainer.put(chunk, next.triggerTick());
	}

	@WrapOperation(method = "sortContainersToTick", at = @At(value = "INVOKE", target = "Ljava/util/function/LongPredicate;test(J)Z"))
	private boolean afterburner$park(LongPredicate check, long chunk, Operation<Boolean> original) {
		boolean runs = original.call(check, chunk);
		if (afterburner$attached) {
			if (!runs) afterburner$toPark.add(chunk);
			else if (!afterburner$parked.isEmpty()) afterburner$parked.remove(chunk);
		}
		return runs;
	}

	@Inject(method = "sortContainersToTick", at = @At("TAIL"))
	private void afterburner$setAside(long currentTick, CallbackInfo ci) {
		if (TickParking.CHECK && !afterburner$parked.isEmpty()) afterburner$check(currentTick);
		LongArrayList found = afterburner$toPark;
		for (int i = 0, n = found.size(); i < n; i++) {
			long chunk = found.getLong(i);
			nextTickForContainer.remove(chunk);
			if (afterburner$parked.add(chunk)) TickParking.parked++;
		}
		found.clear();
	}

	/** The self-check ({@link TickParking}): would vanilla have run any chunk set aside now? Those run now, as in vanilla. */
	@Unique
	private void afterburner$check(long currentTick) {
		for (LongIterator it = afterburner$parked.iterator(); it.hasNext(); ) {
			long chunk = it.nextLong();
			// One back in the map (a tick scheduled since) was asked by the walk, as in vanilla.
			if (nextTickForContainer.containsKey(chunk)) continue;
			LevelChunkTicks<T> container = allContainers.get(chunk);
			ScheduledTick<T> next = container != null ? container.peek() : null;
			if (next == null || next.triggerTick() > currentTick) continue;
			boolean runs = tickCheck.test(chunk);
			TickParking.checked(chunk, runs);
			if (runs) {
				it.remove();
				containersToTick.add(container);
			}
		}
	}

	@Inject(method = "addContainer", at = @At("HEAD"))
	private void afterburner$added(ChunkPos pos, LevelChunkTicks<T> container, CallbackInfo ci) {
		if (!afterburner$parked.isEmpty()) afterburner$parked.remove(pos.pack());
	}

	@Inject(method = "removeContainer", at = @At("HEAD"))
	private void afterburner$removed(ChunkPos pos, CallbackInfo ci) {
		if (!afterburner$parked.isEmpty()) afterburner$parked.remove(pos.pack());
	}
}
