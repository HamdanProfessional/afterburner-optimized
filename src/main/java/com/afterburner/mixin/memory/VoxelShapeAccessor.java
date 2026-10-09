package com.afterburner.mixin.memory;

import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A shape's cells, for {@link com.afterburner.memory.SharedShapes}. */
@Mixin(VoxelShape.class)
public interface VoxelShapeAccessor {
	@Accessor("shape")
	DiscreteVoxelShape afterburner$shape();
}
