package com.afterburner.mixin.ai;

import com.afterburner.ai.AiCheck;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.schedule.Activity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every tick, a mob's brain walks a map of maps of sets to find its behaviors (about a hundred for a villager), once to
 * start the ones that can start and once more to find the running ones. They only change when activities are added, so
 * this keeps them in plain arrays, in the same order the maps give, and walks those.
 */
@Mixin(Brain.class)
public abstract class BrainMixin<E extends LivingEntity> {
	@Shadow
	@Final
	private Map<Integer, Map<Activity, Set<BehaviorControl<? super E>>>> availableBehaviorsByPriority;
	@Shadow
	@Final
	private Set<Activity> activeActivities;

	/** For each (priority, activity) entry of the map in order: its activity, and its behaviors. Null when out of date. */
	@Unique
	private Activity @Nullable [] afterburner$activities;
	@Unique
	private BehaviorControl<? super E>[][] afterburner$behaviors;

	@Inject(method = {"addActivity", "removeAllBehaviors"}, at = @At("HEAD"))
	private void afterburner$onBehaviorsChanged(CallbackInfo ci) {
		afterburner$activities = null;
	}

	@Unique
	@SuppressWarnings("unchecked")
	private Activity[] afterburner$entries() {
		Activity[] activities = afterburner$activities;
		if (activities != null) return activities;
		List<Activity> keys = new ArrayList<>();
		List<BehaviorControl<? super E>[]> values = new ArrayList<>();
		for (Map<Activity, Set<BehaviorControl<? super E>>> byActivity : availableBehaviorsByPriority.values()) {
			for (Map.Entry<Activity, Set<BehaviorControl<? super E>>> entry : byActivity.entrySet()) {
				keys.add(entry.getKey());
				values.add(entry.getValue().toArray(BehaviorControl[]::new));
			}
		}
		afterburner$behaviors = values.toArray(BehaviorControl[][]::new);
		return afterburner$activities = keys.toArray(Activity[]::new);
	}

	/**
	 * @author Afterburner
	 * @reason Walk the behaviors in arrays instead of nested maps; same behaviors, same order.
	 */
	@Overwrite
	private void startEachNonRunningBehavior(ServerLevel level, E body) {
		long time = level.getGameTime();
		Activity[] activities = afterburner$entries();
		BehaviorControl<? super E>[][] behaviors = afterburner$behaviors;
		for (int i = 0; i < activities.length; i++) {
			// Checked for each entry as vanilla does, since starting a behavior can change the activity.
			if (activeActivities.contains(activities[i])) {
				for (BehaviorControl<? super E> behavior : behaviors[i]) {
					if (behavior.getStatus() == Behavior.Status.STOPPED) behavior.tryStart(level, body, time);
				}
			}
		}
	}

	/**
	 * @author Afterburner
	 * @reason Walk the behaviors in arrays instead of nested maps; same behaviors, same order.
	 */
	@Overwrite
	@Deprecated
	public List<BehaviorControl<? super E>> getRunningBehaviors() {
		List<BehaviorControl<? super E>> running = new ObjectArrayList<>();
		afterburner$entries();
		for (BehaviorControl<? super E>[] behaviors : afterburner$behaviors) {
			for (BehaviorControl<? super E> behavior : behaviors) {
				if (behavior.getStatus() == Behavior.Status.RUNNING) running.add(behavior);
			}
		}
		if (AiCheck.ENABLED) afterburner$check();
		return running;
	}

	/** For testing: the arrays still hold what walking the maps gives. */
	@Unique
	private void afterburner$check() {
		List<Object> expected = new ArrayList<>(), found = new ArrayList<>();
		for (Map<Activity, Set<BehaviorControl<? super E>>> byActivity : availableBehaviorsByPriority.values()) {
			for (Map.Entry<Activity, Set<BehaviorControl<? super E>>> entry : byActivity.entrySet()) {
				expected.add(entry.getKey());
				expected.addAll(entry.getValue());
			}
		}
		Activity[] activities = afterburner$entries();
		for (int i = 0; i < activities.length; i++) {
			found.add(activities[i]);
			found.addAll(List.of(afterburner$behaviors[i]));
		}
		AiCheck.result("brain", expected.equals(found), "vanilla " + expected.size() + " entries, got " + found.size());
	}
}
