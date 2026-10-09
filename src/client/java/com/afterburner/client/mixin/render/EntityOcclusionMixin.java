package com.afterburner.client.mixin.render;

import com.afterburner.client.render.OccludedObject;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Remembers what the last occlusion test said about it (see {@link com.afterburner.client.render.EntityCulling}). */
@Mixin(Entity.class)
public class EntityOcclusionMixin implements OccludedObject {
	@Unique
	private long afterburner$hiddenAt = -1;
	@Unique
	private double afterburner$hiddenX, afterburner$hiddenY, afterburner$hiddenZ;

	@Override
	public long afterburner$hiddenAt() {
		return afterburner$hiddenAt;
	}

	@Override
	public double afterburner$movedSq(double x, double y, double z) {
		double dx = x - afterburner$hiddenX, dy = y - afterburner$hiddenY, dz = z - afterburner$hiddenZ;
		return dx * dx + dy * dy + dz * dz;
	}

	@Override
	public void afterburner$setHiddenAt(long frame, double x, double y, double z) {
		afterburner$hiddenAt = frame;
		afterburner$hiddenX = x;
		afterburner$hiddenY = y;
		afterburner$hiddenZ = z;
	}
}
