package com.afterburner.collision;

import com.afterburner.mixin.collision.LevelEntitiesAccessor;
import com.afterburner.mixin.collision.LevelEntityGetterAdapterAccessor;
import com.google.common.collect.ImmutableList;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.Continuation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The entities a box bumps into, for every caller of {@code Level.getEntityCollisions}: entities moving, and the "is
 * anything solid here" checks items, XP orbs, arrows in the ground and mobs in water make every tick. Vanilla looks at
 * every entity near the box, but almost none can be bumped into (only boats, minecarts, shulkers and the like). This skips
 * the sections that have none of those. Within the sections that do, it checks the same entities in the same order, so
 * the answer is the same.
 */
public final class EntityBumps {
	private EntityBumps() {
	}

	/** {@code EntityGetter.getEntityCollisions}, the same shapes in the same order. */
	public static List<VoxelShape> collisions(Level level, @Nullable Entity source, AABB testArea) {
		LevelEntityGetter<Entity> entities = ((LevelEntitiesAccessor) level).afterburner$entities();
		if (source != null && !HardEntities.bumpsOnlyIntoHard(source) || !(entities instanceof LevelEntityGetterAdapter<Entity> adapter)) {
			return vanilla(level, source, testArea);
		}
		List<VoxelShape> shapes = fast(level, adapter, source, testArea);
		if (CollisionCheck.ENABLED) {
			List<VoxelShape> expected = vanilla(level, source, testArea);
			boolean same = expected.size() == shapes.size();
			for (int i = 0; same && i < shapes.size(); i++) same = expected.get(i).toAabbs().equals(shapes.get(i).toAabbs());
			CollisionCheck.result("entity", same, "vanilla " + expected.size() + " shapes, got " + shapes.size());
		}
		return shapes;
	}

	/** Vanilla's {@code EntityGetter.getEntityCollisions}. */
	private static List<VoxelShape> vanilla(Level level, @Nullable Entity source, AABB testArea) {
		if (testArea.getSize() < 1.0E-7) return List.of();
		Predicate<Entity> canCollide = source == null ? EntitySelector.CAN_BE_COLLIDED_WITH : EntitySelector.NO_SPECTATORS.and(source::canCollideWith);
		List<Entity> colliding = level.getEntities(source, testArea.inflate(1.0E-7), canCollide);
		return shapes(colliding);
	}

	/** Vanilla's lookup, but skipping sections without any entity that can be bumped into. */
	@SuppressWarnings("unchecked")
	private static List<VoxelShape> fast(Level level, LevelEntityGetterAdapter<Entity> adapter, @Nullable Entity source, AABB testArea) {
		if (testArea.getSize() < 1.0E-7) return List.of();
		AABB bb = testArea.inflate(1.0E-7);
		List<Entity> found = new ArrayList<>();
		AbortableIterationConsumer<Entity> collect = entity -> {
			if (entity != source && bumps(source, entity)) found.add(entity);
			return Continuation.CONTINUE;
		};
		((LevelEntityGetterAdapterAccessor<Entity>) adapter).afterburner$sections().forEachAccessibleNonEmptySection(bb, section ->
				((HardEntityCount) section).afterburner$hardEntities() == 0 ? Continuation.CONTINUE : section.getEntities(bb, collect));
		for (EnderDragonPart part : level.dragonParts()) {
			if (part != source && part.parentMob != source && bumps(source, part) && bb.intersects(part.getBoundingBox())) found.add(part);
		}
		return shapes(found);
	}

	/** Vanilla's test: {@code CAN_BE_COLLIDED_WITH} without a source, else not a spectator and {@code source.canCollideWith}. */
	private static boolean bumps(@Nullable Entity source, Entity entity) {
		return !entity.isSpectator() && (source == null ? entity.canBeCollidedWith(null) : source.canCollideWith(entity));
	}

	private static List<VoxelShape> shapes(List<Entity> entities) {
		if (entities.isEmpty()) return List.of();
		ImmutableList.Builder<VoxelShape> shapes = ImmutableList.builderWithExpectedSize(entities.size());
		for (Entity entity : entities) shapes.add(Shapes.create(entity.getBoundingBox()));
		return shapes.build();
	}
}
