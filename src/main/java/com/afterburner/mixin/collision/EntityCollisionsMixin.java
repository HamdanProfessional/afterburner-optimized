package com.afterburner.mixin.collision;

import com.afterburner.collision.EntityBumps;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

/**
 * Every world's lookup of the entities a box bumps into skips the sections with no entity that can be bumped into, see
 * {@link EntityBumps}. Vanilla's is the interface's default method, which {@code Level} doesn't override, so this adds the
 * override: entities moving use it, and so do the checks for room items, XP orbs, arrows and mobs in water make each tick.
 */
@Mixin(Level.class)
public abstract class EntityCollisionsMixin implements EntityGetter {
	@Override
	public List<VoxelShape> getEntityCollisions(@Nullable Entity source, AABB testArea) {
		return EntityBumps.collisions((Level) (Object) this, source, testArea);
	}
}
