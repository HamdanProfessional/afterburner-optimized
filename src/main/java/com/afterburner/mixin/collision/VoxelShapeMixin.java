package com.afterburner.mixin.collision;

import com.afterburner.collision.BoxShape;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Remembers whether a shape is one box, for {@link BlockCollisionsMixin}. */
@Mixin(VoxelShape.class)
public abstract class VoxelShapeMixin implements BoxShape {
	/** 0 not worked out yet, 1 one box, 2 not. Two threads may both work it out; they get the same. */
	@Unique
	private byte afterburner$box;

	@Override
	public boolean afterburner$isBox() {
		byte box = afterburner$box;
		if (box == 0) afterburner$box = box = (byte) (((VoxelShape) (Object) this).toAabbs().size() == 1 ? 1 : 2);
		return box == 1;
	}
}
