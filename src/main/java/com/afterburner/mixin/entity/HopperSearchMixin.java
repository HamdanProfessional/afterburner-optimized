package com.afterburner.mixin.entity;

import com.afterburner.entity.HopperSearch;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.function.Predicate;

/** A hopper's search for a minecart that holds items skips the entity sections with none, see {@link HopperSearch}. */
@Mixin(HopperBlockEntity.class)
public abstract class HopperSearchMixin {
	@WrapOperation(method = "getEntityContainer", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
	private static List<Entity> afterburner$containers(Level level, Entity except, AABB box, Predicate<? super Entity> selector,
			Operation<List<Entity>> original) {
		List<Entity> found = except == null ? HopperSearch.containers(level, box, selector) : null;
		if (found == null) return original.call(level, except, box, selector);
		if (HopperSearch.CHECK) HopperSearch.check(found, original.call(level, except, box, selector));
		return found;
	}
}
