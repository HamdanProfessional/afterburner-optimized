package com.afterburner.mixin.redstone;

import net.minecraft.world.level.block.RedStoneWireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether wires give a signal right now (off while a wire works out its own power), for {@link com.afterburner.redstone.WireReads}. */
@Mixin(RedStoneWireBlock.class)
public interface RedstoneWireAccessor {
	@Accessor("shouldSignal")
	void afterburner$setShouldSignal(boolean shouldSignal);
}
