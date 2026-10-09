package com.afterburner.worldgen;

import com.afterburner.Afterburner;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The room left for the pieces of a jigsaw structure (villages, trial chambers, ancient cities, bastions): a box with the
 * boxes of the pieces placed so far taken out. Vanilla keeps the room as a voxel shape and cuts each new piece out of it,
 * so the shape gets more corner lines with every piece, and every test whether a piece fits and every cut goes through
 * all of them. This keeps the boxes in a list instead. Every box has whole-block corners and the tested boxes are shrunk
 * by a quarter block, so no two corners come within vanilla's rounding (1.0E-7) of each other, and "the shrunk box lies
 * in the room" is the same as "it lies in the outer box and overlaps none of the cut-out boxes". To any other code this
 * looks like its outer box.
 */
public final class FreeSpace extends VoxelShape {
	/** {@code -Dafterburner.checkStructures=true}: the room is also kept vanilla's way and every test compared. */
	public static final boolean CHECK = Boolean.getBoolean("afterburner.checkStructures");
	private static final AtomicLong CHECKED = new AtomicLong(), DIFFERENT = new AtomicLong();

	private final double minX, minY, minZ, maxX, maxY, maxZ;
	private final DoubleList xs, ys, zs;
	private final @Nullable Hole holes;
	/** Check mode: the same room built vanilla's way. */
	private final @Nullable VoxelShape vanilla;

	private record Hole(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, @Nullable Hole next) {
	}

	private FreeSpace(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, @Nullable Hole holes,
			@Nullable VoxelShape vanilla) {
		super(full());
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.maxX = maxX;
		this.maxY = maxY;
		this.maxZ = maxZ;
		this.xs = DoubleArrayList.wrap(new double[]{minX, maxX});
		this.ys = DoubleArrayList.wrap(new double[]{minY, maxY});
		this.zs = DoubleArrayList.wrap(new double[]{minZ, maxZ});
		this.holes = holes;
		this.vanilla = vanilla;
	}

	private static BitSetDiscreteVoxelShape full() {
		BitSetDiscreteVoxelShape shape = new BitSetDiscreteVoxelShape(1, 1, 1);
		shape.fill(0, 0, 0);
		return shape;
	}

	/** {@code outer} with {@code start} cut out, or vanilla's shape if either isn't a plain box. */
	public static VoxelShape around(VoxelShape outer, VoxelShape start, Supplier<VoxelShape> vanilla) {
		if (!isBox(outer) || !isBox(start)) return vanilla.get();
		Hole hole = new Hole(start.min(Direction.Axis.X), start.min(Direction.Axis.Y), start.min(Direction.Axis.Z),
				start.max(Direction.Axis.X), start.max(Direction.Axis.Y), start.max(Direction.Axis.Z), null);
		return new FreeSpace(outer.min(Direction.Axis.X), outer.min(Direction.Axis.Y), outer.min(Direction.Axis.Z),
				outer.max(Direction.Axis.X), outer.max(Direction.Axis.Y), outer.max(Direction.Axis.Z), hole, CHECK ? vanilla.get() : null);
	}

	/** The room inside one piece, for the pieces attached inside it. */
	public static VoxelShape inside(AABB box, Supplier<VoxelShape> vanilla) {
		VoxelShape shape = vanilla.get();
		if (!isBox(shape)) return shape;
		return new FreeSpace(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, null, CHECK ? shape : null);
	}

	private static boolean isBox(VoxelShape shape) {
		return !(shape instanceof FreeSpace) && !shape.isEmpty() && shape.toAabbs().size() == 1;
	}

	/** Vanilla's {@code !Shapes.joinIsNotEmpty(room, tested, ONLY_SECOND)}: whether the tested box lies in the room. */
	public boolean fits(VoxelShape tested) {
		boolean fits = isBox(tested) ? fitsBox(tested) : !Shapes.joinIsNotEmpty(toVanilla(), tested, BooleanOp.ONLY_SECOND);
		if (vanilla != null) check(fits, !Shapes.joinIsNotEmpty(vanilla, tested, BooleanOp.ONLY_SECOND));
		return fits;
	}

	private boolean fitsBox(VoxelShape tested) {
		double x0 = tested.min(Direction.Axis.X), y0 = tested.min(Direction.Axis.Y), z0 = tested.min(Direction.Axis.Z);
		double x1 = tested.max(Direction.Axis.X), y1 = tested.max(Direction.Axis.Y), z1 = tested.max(Direction.Axis.Z);
		if (x0 < minX || y0 < minY || z0 < minZ || x1 > maxX || y1 > maxY || z1 > maxZ) return false;
		for (Hole h = holes; h != null; h = h.next) {
			if (x0 < h.maxX && h.minX < x1 && y0 < h.maxY && h.minY < y1 && z0 < h.maxZ && h.minZ < z1) return false;
		}
		return true;
	}

	/** Vanilla's {@code Shapes.joinUnoptimized(room, cut, ONLY_FIRST)}: the room with a placed piece's box taken out. */
	public VoxelShape without(VoxelShape cut) {
		if (!isBox(cut)) return Shapes.joinUnoptimized(toVanilla(), cut, BooleanOp.ONLY_FIRST);
		Hole hole = new Hole(cut.min(Direction.Axis.X), cut.min(Direction.Axis.Y), cut.min(Direction.Axis.Z),
				cut.max(Direction.Axis.X), cut.max(Direction.Axis.Y), cut.max(Direction.Axis.Z), holes);
		return new FreeSpace(minX, minY, minZ, maxX, maxY, maxZ, hole,
				vanilla != null ? Shapes.joinUnoptimized(vanilla, cut, BooleanOp.ONLY_FIRST) : null);
	}

	/** The room as vanilla would have it, for anything unexpected. */
	private VoxelShape toVanilla() {
		if (vanilla != null) return vanilla;
		VoxelShape shape = Shapes.create(minX, minY, minZ, maxX, maxY, maxZ);
		// Cut in the order vanilla did: oldest first.
		ArrayList<Hole> order = new ArrayList<>();
		for (Hole h = holes; h != null; h = h.next) order.add(h);
		for (int i = order.size() - 1; i >= 0; i--) {
			Hole h = order.get(i);
			shape = Shapes.joinUnoptimized(shape, Shapes.create(h.minX, h.minY, h.minZ, h.maxX, h.maxY, h.maxZ), BooleanOp.ONLY_FIRST);
		}
		return shape;
	}

	/** The room itself, or vanilla's version of a {@link FreeSpace}, for joins this doesn't handle. */
	public static VoxelShape real(VoxelShape shape) {
		return shape instanceof FreeSpace free ? free.toVanilla() : shape;
	}

	@Override
	public DoubleList getCoords(Direction.Axis axis) {
		return switch (axis) {
			case X -> xs;
			case Y -> ys;
			case Z -> zs;
		};
	}

	private static void check(boolean fits, boolean vanilla) {
		CHECKED.incrementAndGet();
		if (fits != vanilla && DIFFERENT.incrementAndGet() <= 20) {
			Afterburner.LOGGER.error("Structure check failed: piece fits {}, vanilla says {}", fits, vanilla);
		}
	}

	/** For benchmark reports, e.g. "structure check: 1,234 compared, 0 different". */
	public static String summary() {
		return "structure check: " + String.format("%,d", CHECKED.get()) + " compared, " + DIFFERENT.get() + " different";
	}
}
