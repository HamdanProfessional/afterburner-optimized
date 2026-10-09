package com.afterburner.mixin.collision;

import com.afterburner.collision.BoxShape;
import com.afterburner.collision.CollisionCheck;
import com.google.common.collect.AbstractIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Finds the blocks an entity's box bumps into. It looks at the same blocks in the same order as vanilla and gives the
 * same answers, but reads blocks straight from the chunk section (vanilla goes through the chunk, which works out the
 * section again for every block), skips air without asking it for its shape, skips the box's outer layer where the
 * section holds nothing that counts there, and counts through the box with plain loops instead of dividing.
 */
@Mixin(BlockCollisions.class)
public abstract class BlockCollisionsMixin<T> extends AbstractIterator<T> {
	@Unique
	private static final ThreadLocal<Boolean> MAKING_VANILLA_TWIN = ThreadLocal.withInitial(() -> false);

	@Shadow
	@Final
	private AABB box;
	@Shadow
	@Final
	private CollisionContext context;
	@Shadow
	@Final
	private BlockPos.MutableBlockPos pos;
	@Shadow
	@Final
	private VoxelShape entityShape;
	@Shadow
	@Final
	private CollisionGetter collisionGetter;
	@Shadow
	@Final
	private boolean onlySuffocatingBlocks;
	@Shadow
	@Final
	private BiFunction<BlockPos.MutableBlockPos, VoxelShape, T> resultProvider;

	@Unique
	private int afterburner$minX, afterburner$minY, afterburner$minZ, afterburner$maxX, afterburner$maxY, afterburner$maxZ;
	/** The next block to look at. */
	@Unique
	private int afterburner$x, afterburner$y, afterburner$z;
	@Unique
	private @Nullable LevelChunk afterburner$chunk;
	@Unique
	private LevelChunkSection @Nullable [] afterburner$sections;
	@Unique
	private int afterburner$minSectionY;
	/** See {@link #afterburner$kinds}. */
	@Unique
	private LevelChunkSection @Nullable [] afterburner$seen;
	@Unique
	private int[] afterburner$seenKinds;
	@Unique
	private int afterburner$nextSeen;
	/** Set on the copy made for {@link CollisionCheck}, which runs vanilla's code. */
	@Unique
	private boolean afterburner$vanilla;
	@Unique
	private @Nullable BlockCollisions<Object[]> afterburner$twin;

	@Shadow
	protected abstract @Nullable BlockGetter getChunk(int x, int z);

	@Inject(method = "<init>(Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/world/phys/shapes/CollisionContext;Lnet/minecraft/world/phys/AABB;ZLjava/util/function/BiFunction;)V",
			at = @At("TAIL"))
	private void afterburner$init(CollisionGetter getter, CollisionContext context, AABB box, boolean onlySuffocating,
			BiFunction<BlockPos.MutableBlockPos, VoxelShape, T> provider, CallbackInfo ci) {
		// The same area as vanilla's cursor.
		afterburner$minX = afterburner$x = Mth.floor(box.minX - 1.0E-7) - 1;
		afterburner$maxX = Mth.floor(box.maxX + 1.0E-7) + 1;
		afterburner$minY = afterburner$y = Mth.floor(box.minY - 1.0E-7) - 1;
		afterburner$maxY = Mth.floor(box.maxY + 1.0E-7) + 1;
		afterburner$minZ = afterburner$z = Mth.floor(box.minZ - 1.0E-7) - 1;
		afterburner$maxZ = Mth.floor(box.maxZ + 1.0E-7) + 1;
		if (!CollisionCheck.ENABLED) return;
		if (MAKING_VANILLA_TWIN.get()) {
			afterburner$vanilla = true;
		} else {
			MAKING_VANILLA_TWIN.set(true);
			try {
				afterburner$twin = new BlockCollisions<>(getter, context, box, onlySuffocating, (p, shape) -> new Object[]{p.immutable(), shape});
			} finally {
				MAKING_VANILLA_TWIN.set(false);
			}
		}
	}

	@Inject(method = "computeNext", at = @At("HEAD"), cancellable = true)
	private void afterburner$computeNext(CallbackInfoReturnable<T> cir) {
		if (afterburner$vanilla) return;
		VoxelShape shape = afterburner$find();
		if (afterburner$twin != null) afterburner$check(shape);
		cir.setReturnValue(shape != null ? resultProvider.apply(pos, shape) : endOfData());
	}

	/** Moves on to the next block the box bumps into and returns its shape there, or null when there are no more. */
	@Unique
	private @Nullable VoxelShape afterburner$find() {
		int minX = afterburner$minX, maxX = afterburner$maxX, minY = afterburner$minY, maxY = afterburner$maxY, maxZ = afterburner$maxZ;
		for (; afterburner$z <= maxZ; afterburner$z++, afterburner$y = minY) {
			int z = afterburner$z;
			int zEdge = z == afterburner$minZ || z == maxZ ? 1 : 0;
			for (; afterburner$y <= maxY; afterburner$y++, afterburner$x = minX) {
				int y = afterburner$y;
				int yzEdges = zEdge + (y == minY || y == maxY ? 1 : 0);
				while (afterburner$x <= maxX) {
					int x = afterburner$x++;
					// Like vanilla's cursor: 0 inside the area, 1 on a face, 2 on an edge, 3 on a corner.
					int type = yzEdges + (x == minX || x == maxX ? 1 : 0);
					if (type == 3) continue;
					BlockState state = afterburner$state(x, y, z, type);
					if (state == null || state.isAir()) continue; // no chunk, or nothing to bump into
					if (onlySuffocatingBlocks && !state.isSuffocating(getChunk(x, z), pos)) continue;
					if (type == 1 && !state.hasLargeCollisionShape()) continue;
					if (type == 2 && !state.is(Blocks.MOVING_PISTON)) continue;
					VoxelShape blockShape = context.getCollisionShape(state, collisionGetter, pos);
					if (blockShape == Shapes.block()) {
						if (box.intersects(x, y, z, x + 1.0, y + 1.0, z + 1.0)) return blockShape.move(pos);
					} else {
						VoxelShape shape = blockShape.move(pos);
						if (!shape.isEmpty() && afterburner$bumps(blockShape, shape, x, y, z)) return shape;
					}
				}
			}
		}
		return null;
	}

	/**
	 * Vanilla's {@code Shapes.joinIsNotEmpty(shape, entityShape, AND)}, which builds merged grids of both shapes' corners to
	 * look for a cell in both. When the block's shape is one box (slabs, farmland, paths, snow), the entity's is its plain
	 * box, and they overlap by more than 5.0E-7 along every axis, there is such a cell: vanilla's grids only run corners
	 * closer than 1.0E-7 together, so the cell where both boxes overlap keeps a width. Anything closer is left to vanilla.
	 */
	@Unique
	private boolean afterburner$bumps(VoxelShape blockShape, VoxelShape shape, int x, int y, int z) {
		if (entityShape.getClass() == ArrayVoxelShape.class && ((BoxShape) blockShape).afterburner$isBox()
				&& afterburner$overlap(blockShape.min(Direction.Axis.X) + x, blockShape.max(Direction.Axis.X) + x, box.minX, box.maxX)
				&& afterburner$overlap(blockShape.min(Direction.Axis.Y) + y, blockShape.max(Direction.Axis.Y) + y, box.minY, box.maxY)
				&& afterburner$overlap(blockShape.min(Direction.Axis.Z) + z, blockShape.max(Direction.Axis.Z) + z, box.minZ, box.maxZ)) {
			return true;
		}
		return Shapes.joinIsNotEmpty(shape, entityShape, BooleanOp.AND);
	}

	@Unique
	private static boolean afterburner$overlap(double min, double max, double boxMin, double boxMax) {
		return Math.min(max, boxMax) - Math.max(min, boxMin) > 5.0E-7;
	}

	/**
	 * The block at a position (also moving {@link #pos} there), or null if its chunk isn't loaded. On the box's faces and
	 * edges only a few kinds of blocks count (see the checks in {@link #afterburner$find}); if the section can't hold any
	 * of them, this gives air without reading the block.
	 */
	@Unique
	private @Nullable BlockState afterburner$state(int x, int y, int z, int type) {
		BlockGetter chunk = getChunk(x, z);
		if (chunk == null) return null;
		pos.set(x, y, z);
		if (chunk != afterburner$chunk) {
			afterburner$chunk = null;
			// Plain chunks of a normal world can be read section by section; anything else is asked directly.
			if (chunk.getClass() == LevelChunk.class && !((LevelChunk) chunk).getLevel().isDebug()) {
				afterburner$chunk = (LevelChunk) chunk;
				afterburner$sections = afterburner$chunk.getSections();
				afterburner$minSectionY = afterburner$chunk.getMinSectionY();
			} else {
				return chunk.getBlockState(pos);
			}
		}
		int index = (y >> 4) - afterburner$minSectionY;
		LevelChunkSection[] sections = afterburner$sections;
		if (index < 0 || index >= sections.length) return Blocks.AIR.defaultBlockState();
		LevelChunkSection section = sections[index];
		if (section.hasOnlyAir()) return Blocks.AIR.defaultBlockState();
		if (type != 0 && (afterburner$kinds(section) & type) == 0) return Blocks.AIR.defaultBlockState();
		return section.getBlockState(x & 15, y & 15, z & 15);
	}

	/**
	 * Which of the blocks that count on the box's faces (1) and edges (2) a section might hold, remembered for the last
	 * few sections looked at. Each section lists the kinds of blocks it holds (it may list some it no longer holds).
	 */
	@Unique
	private int afterburner$kinds(LevelChunkSection section) {
		LevelChunkSection[] seen = afterburner$seen;
		if (seen == null) {
			seen = afterburner$seen = new LevelChunkSection[4];
			afterburner$seenKinds = new int[4];
		}
		for (int i = 0; i < 4; i++) {
			if (seen[i] == section) return afterburner$seenKinds[i];
		}
		int kinds = (section.maybeHas(BlockState::hasLargeCollisionShape) ? 1 : 0) | (section.maybeHas(state -> state.is(Blocks.MOVING_PISTON)) ? 2 : 0);
		int slot = afterburner$nextSeen++ & 3;
		seen[slot] = section;
		afterburner$seenKinds[slot] = kinds;
		return kinds;
	}

	@Unique
	private void afterburner$check(@Nullable VoxelShape shape) {
		BlockCollisions<Object[]> twin = Objects.requireNonNull(afterburner$twin);
		Object[] expected = twin.hasNext() ? twin.next() : null;
		boolean same = expected == null ? shape == null
				: shape != null && expected[0].equals(pos) && sameShape((VoxelShape) expected[1], shape);
		CollisionCheck.result("block", same, expected == null ? "vanilla found nothing, got " + pos
				: "vanilla found " + expected[0] + " " + ((VoxelShape) expected[1]).toAabbs() + ", got " + (shape == null ? "nothing" : pos + " " + shape.toAabbs()));
	}

	@Unique
	private static boolean sameShape(VoxelShape a, VoxelShape b) {
		List<AABB> boxesA = a.toAabbs(), boxesB = b.toAabbs();
		return boxesA.equals(boxesB);
	}
}
