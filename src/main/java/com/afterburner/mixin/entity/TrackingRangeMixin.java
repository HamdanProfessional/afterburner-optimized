package com.afterburner.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Every tick, for every entity and every player, vanilla works out how far the entity is sent: its own range, or a rider's
 * if that's further, read from a stream of its riders. Nearly nothing has riders; then there's no stream to make.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackingRangeMixin {
	@WrapOperation(method = "getEffectiveRange", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getIndirectPassengers()Ljava/lang/Iterable;"))
	private Iterable<Entity> afterburner$riders(Entity entity, Operation<Iterable<Entity>> original) {
		return entity.isVehicle() ? original.call(entity) : List.of();
	}
}
