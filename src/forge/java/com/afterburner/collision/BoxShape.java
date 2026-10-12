package com.afterburner.collision;

/** Added to every {@link net.minecraft.world.phys.shapes.VoxelShape}. */
public interface BoxShape {
	/** Whether the shape is one box (a slab, farmland, a path), worked out once per shape. */
	boolean afterburner$isBox();
}
