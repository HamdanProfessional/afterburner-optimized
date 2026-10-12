package com.afterburner.mixin.collision;

import com.afterburner.collision.HardEntities;
import com.afterburner.collision.HardEntityCount;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Counts the entities in each section that might be bumped into, so sections without any can be skipped. */
@Mixin(EntitySection.class)
public abstract class EntitySectionMixin<T extends EntityAccess> implements HardEntityCount {
	@Unique
	private int afterburner$hard;

	@Inject(method = "add", at = @At("HEAD"))
	private void afterburner$onAdd(T entity, CallbackInfo ci) {
		if (entity instanceof Entity e && HardEntities.isHard(e)) afterburner$hard++;
	}

	@Inject(method = "remove", at = @At("RETURN"))
	private void afterburner$onRemove(T entity, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && entity instanceof Entity e && HardEntities.isHard(e)) afterburner$hard--;
	}

	@Override
	public int afterburner$hardEntities() {
		return afterburner$hard;
	}
}
