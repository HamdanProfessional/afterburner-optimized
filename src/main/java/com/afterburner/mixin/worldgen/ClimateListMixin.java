package com.afterburner.mixin.worldgen;

import com.afterburner.worldgen.ClimateIndex;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Biome lookups for new land go through {@link ClimateIndex} instead of vanilla's object tree. */
@Mixin(Climate.ParameterList.class)
public abstract class ClimateListMixin<T> {
	@Unique
	private ClimateIndex afterburner$index;
	@Unique
	private boolean afterburner$built;

	@SuppressWarnings("unchecked")
	@WrapMethod(method = "findValueIndex(Lnet/minecraft/world/level/biome/Climate$TargetPoint;)Ljava/lang/Object;")
	private T afterburner$findValueIndex(Climate.TargetPoint target, Operation<T> original) {
		ClimateIndex index = afterburner$index;
		if (index == null) {
			if (afterburner$built) return original.call(target);
			synchronized (this) {
				if (!afterburner$built) {
					afterburner$index = ClimateIndex.of((Climate.ParameterList<?>) (Object) this);
					afterburner$built = true;
				}
				index = afterburner$index;
			}
			if (index == null) return original.call(target);
		}
		if (!ClimateIndex.CHECK) return (T) index.search(target);
		// Both from the same starting biome; vanilla's search then leaves its own answer as the next start.
		Object start = index.last();
		index.search(target);
		Object ours = index.last();
		index.restore(start);
		T vanilla = original.call(target);
		ClimateIndex.compare(ours, index.last());
		return vanilla;
	}
}
