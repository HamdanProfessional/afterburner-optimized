package com.afterburner.client.mixin.render;

import net.minecraft.client.renderer.block.BlockModelLighter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockModelLighter.class)
public interface BlockModelLighterAccessor {
	@Accessor("CACHE")
	static ThreadLocal<BlockModelLighter.Cache> afterburner$cache() {
		throw new AssertionError();
	}
}
