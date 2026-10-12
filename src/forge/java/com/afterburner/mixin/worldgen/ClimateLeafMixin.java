package com.afterburner.mixin.worldgen;

import com.afterburner.worldgen.ClimateLeaf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(targets = "net.minecraft.world.level.biome.Climate$RTree$Leaf")
public abstract class ClimateLeafMixin implements ClimateLeaf {
	@Unique
	private int afterburner$index;

	@Override
	public int afterburner$index() {
		return afterburner$index;
	}

	@Override
	public void afterburner$setIndex(int index) {
		afterburner$index = index;
	}
}
