package com.afterburner.mixin.memory;

import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.BitSet;

/** Which cells of a shape are full, for {@link com.afterburner.memory.SharedShapes}. */
@Mixin(BitSetDiscreteVoxelShape.class)
public interface BitSetShapeAccessor {
	@Accessor("storage")
	BitSet afterburner$storage();
}
