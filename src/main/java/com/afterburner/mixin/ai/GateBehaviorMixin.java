package com.afterburner.mixin.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.GateBehavior;
import net.minecraft.world.entity.ai.behavior.ShufflingList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Groups of behaviors (a villager has several) start and tick their members through streams every tick. These are the
 * same steps as plain loops: the same members, in the same order, each checked right before it's used.
 */
@Mixin(GateBehavior.class)
public abstract class GateBehaviorMixin<E extends LivingEntity> {
	@Shadow
	@Final
	private GateBehavior.OrderPolicy orderPolicy;
	@Shadow
	@Final
	private GateBehavior.RunningPolicy runningPolicy;
	@Shadow
	@Final
	private ShufflingList<BehaviorControl<? super E>> behaviors;
	@Shadow
	private Behavior.Status status;

	@Shadow
	protected abstract boolean hasRequiredMemories(E body);

	@Shadow
	public abstract void doStop(ServerLevel level, E body, long timestamp);

	/**
	 * @author Afterburner
	 * @reason Loops instead of streams; same steps in the same order.
	 */
	@Overwrite
	public final boolean tryStart(ServerLevel level, E body, long timestamp) {
		if (!hasRequiredMemories(body)) return false;
		status = Behavior.Status.RUNNING;
		orderPolicy.apply(behaviors);
		if (runningPolicy == GateBehavior.RunningPolicy.RUN_ONE) {
			// filter(stopped).filter(tryStart).findFirst()
			for (BehaviorControl<? super E> behavior : behaviors) {
				if (behavior.getStatus() == Behavior.Status.STOPPED && behavior.tryStart(level, body, timestamp)) break;
			}
		} else if (runningPolicy == GateBehavior.RunningPolicy.TRY_ALL) {
			for (BehaviorControl<? super E> behavior : behaviors) {
				if (behavior.getStatus() == Behavior.Status.STOPPED) behavior.tryStart(level, body, timestamp);
			}
		} else {
			runningPolicy.apply(behaviors.stream(), level, body, timestamp);
		}
		return true;
	}

	/**
	 * @author Afterburner
	 * @reason Loops instead of streams; same steps in the same order.
	 */
	@Overwrite
	public final void tickOrStop(ServerLevel level, E body, long timestamp) {
		for (BehaviorControl<? super E> behavior : behaviors) {
			if (behavior.getStatus() == Behavior.Status.RUNNING) behavior.tickOrStop(level, body, timestamp);
		}
		for (BehaviorControl<? super E> behavior : behaviors) {
			if (behavior.getStatus() == Behavior.Status.RUNNING) return;
		}
		doStop(level, body, timestamp);
	}
}
