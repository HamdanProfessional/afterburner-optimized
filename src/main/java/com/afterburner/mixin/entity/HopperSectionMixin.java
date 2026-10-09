package com.afterburner.mixin.entity;

import com.afterburner.entity.HopperSearch;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Counts the entities in each section that hold items, so hoppers can skip the sections without any, see {@link HopperSearch}. */
@Mixin(EntitySection.class)
public abstract class HopperSectionMixin<T extends EntityAccess> implements HopperSearch.Count {
	@Unique
	private int afterburner$containers;

	@Inject(method = "add", at = @At("HEAD"))
	private void afterburner$onAdd(T entity, CallbackInfo ci) {
		if (HopperSearch.holdsItems(entity)) afterburner$containers++;
	}

	@Inject(method = "remove", at = @At("RETURN"))
	private void afterburner$onRemove(T entity, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && HopperSearch.holdsItems(entity)) afterburner$containers--;
	}

	@Override
	public int afterburner$containers() {
		return afterburner$containers;
	}
}
