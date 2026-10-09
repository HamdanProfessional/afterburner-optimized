package com.afterburner.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Every entity checks every tick whether it stands in rain, only to put out fire, and putting out fire does nothing to an
 * entity that isn't burning. While it rains that check looks up the sky and the biome twice; it's only made while burning.
 */
@Mixin(Entity.class)
public abstract class MobTicksEntityMixin {
	@WrapOperation(method = "applyEffectsFromBlocks(Ljava/util/List;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;isInRain()Z"))
	private boolean afterburner$rainOnFire(Entity self, Operation<Boolean> original) {
		return self.getRemainingFireTicks() > 0 && original.call(self);
	}
}
