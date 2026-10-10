package com.afterburner.memory;

import com.afterburner.mixin.memory.BitSetShapeAccessor;
import com.afterburner.mixin.memory.VoxelShapeAccessor;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.Map;

/**
 * Blocks that work out a shape for each of their states (walls, fences, panes, redstone dust...) each make their own,
 * though most are the same as another block's: every kind of wall makes the same few hundred. The first of each shape is
 * kept and handed to the blocks after it. Shapes don't change once made, so a shared one answers the same as the copy it
 * stands in for. Only shapes made for block states are shared, and the full and empty block are left as they are.
 */
public final class SharedShapes {
	private static final Map<Key, VoxelShape> SHAPES = new HashMap<>();
	private static int made, shared;

	private SharedShapes() {
	}

	public static synchronized VoxelShape share(VoxelShape shape) {
		// The full and empty block stay themselves: the game compares shapes with them by identity.
		if (shape == Shapes.block() || shape == Shapes.empty()) return shape;
		Class<?> type = shape.getClass();
		if (type != ArrayVoxelShape.class && type != CubeVoxelShape.class) return shape;
		if (((VoxelShapeAccessor) shape).afterburner$shape().getClass() != BitSetDiscreteVoxelShape.class) return shape;
		made++;
		VoxelShape kept = SHAPES.putIfAbsent(new Key(shape), shape);
		if (kept == null) return shape;
		shared++;
		return kept;
	}

	/** For reports, e.g. "Block shapes: 9,876 of 14,321 shared". */
	public static synchronized String summary() {
		return "Block shapes: " + String.format("%,d", shared) + " of " + String.format("%,d", made) + " shared";
	}

	/** A shape compared by what it holds: its kind, its cells and where its cell edges are. */
	private static final class Key {
		private static final Direction.Axis[] AXES = Direction.Axis.values();
		private final VoxelShape shape;
		private final int hash;

		Key(VoxelShape shape) {
			this.shape = shape;
			DiscreteVoxelShape cells = ((VoxelShapeAccessor) shape).afterburner$shape();
			int h = shape.getClass().getName().hashCode();
			for (Direction.Axis axis : AXES) {
				h = 31 * h + cells.getSize(axis);
				h = 31 * h + shape.getCoords(axis).hashCode();
			}
			this.hash = 31 * h + ((BitSetShapeAccessor) cells).afterburner$storage().hashCode();
		}

		@Override
		public int hashCode() {
			return this.hash;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Key other) || other.hash != this.hash || other.shape.getClass() != this.shape.getClass()) return false;
			DiscreteVoxelShape a = ((VoxelShapeAccessor) this.shape).afterburner$shape(), b = ((VoxelShapeAccessor) other.shape).afterburner$shape();
			for (Direction.Axis axis : AXES) {
				if (a.getSize(axis) != b.getSize(axis) || a.firstFull(axis) != b.firstFull(axis) || a.lastFull(axis) != b.lastFull(axis)
						|| !this.shape.getCoords(axis).equals(other.shape.getCoords(axis))) {
					return false;
				}
			}
			return ((BitSetShapeAccessor) a).afterburner$storage().equals(((BitSetShapeAccessor) b).afterburner$storage());
		}
	}
}
