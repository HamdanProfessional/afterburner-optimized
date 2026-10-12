package com.afterburner.collision;

import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;

/**
 * Entities only bump into entities that say they can be bumped into (boats, minecarts, shulkers, happy ghasts and any
 * modded entity that overrides {@link Entity#canBeCollidedWith}); for every other entity that method is vanilla's,
 * which always says no. These are worked out once per entity class.
 */
public final class HardEntities {
	/** Classes that override {@link Entity#canBeCollidedWith}, so they might be bumped into. */
	private static final ClassValue<Boolean> HARD = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			return declaredIn(type, "canBeCollidedWith") != Entity.class;
		}
	};
	/** Classes using vanilla's {@link Entity#canCollideWith}, which only bumps into entities that say they can be bumped into. */
	private static final ClassValue<Boolean> PLAIN = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			return declaredIn(type, "canCollideWith") == Entity.class;
		}
	};

	private HardEntities() {
	}

	public static boolean isHard(Entity entity) {
		return HARD.get(entity.getClass());
	}

	public static boolean bumpsOnlyIntoHard(Entity entity) {
		return PLAIN.get(entity.getClass());
	}

	private static Class<?> declaredIn(Class<?> type, String name) {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			for (Method method : c.getDeclaredMethods()) {
				if (method.getName().equals(name) && method.getParameterCount() == 1 && method.getParameterTypes()[0] == Entity.class) return c;
			}
		}
		return Entity.class;
	}
}
