package com.afterburner.mixin.light;

import com.afterburner.light.LightStorageAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Block light spreading, the same steps in the same order as vanilla, but each neighbor's light data is looked up once
 * instead of twice (vanilla checks the section has data, then looks it up again to read the value), and its section
 * isn't worked out again when it's the same one.
 */
@SuppressWarnings("rawtypes")
@Mixin(BlockLightEngine.class)
public abstract class BlockLightEngineMixin extends LightEngine {
	@Shadow
	@Final
	private BlockPos.MutableBlockPos mutablePos;

	private BlockLightEngineMixin() {
		super(null, null);
	}

	@Shadow
	private int getEmission(long blockNode, BlockState state) {
		throw new AssertionError();
	}

	/**
	 * @author Afterburner
	 * @reason One light data lookup per neighbor.
	 */
	@Overwrite
	@Override
	protected void propagateIncrease(long fromNode, long increaseData, int fromLevel) {
		LightStorageAccess light = (LightStorageAccess) storage;
		BlockState fromState = null;
		int fx = BlockPos.getX(fromNode), fy = BlockPos.getY(fromNode), fz = BlockPos.getZ(fromNode);
		long fromSection = SectionPos.asLong(fx >> 4, fy >> 4, fz >> 4);
		for (Direction direction : PROPAGATION_DIRECTIONS) {
			if (!LightEngine.QueueEntry.shouldPropagateInDirection(increaseData, direction)) continue;
			int tx = fx + direction.getStepX(), ty = fy + direction.getStepY(), tz = fz + direction.getStepZ();
			long toSection = ((tx ^ fx) | (ty ^ fy) | (tz ^ fz)) >>> 4 == 0 ? fromSection : SectionPos.asLong(tx >> 4, ty >> 4, tz >> 4);
			DataLayer layer = light.afterburner$layer(toSection);
			if (layer == null) continue;
			int toLevel = layer.get(tx & 15, ty & 15, tz & 15);
			if (fromLevel - 1 <= toLevel) continue;
			BlockState toState = getState(mutablePos.set(tx, ty, tz));
			int newToLevel = fromLevel - getOpacity(toState);
			if (newToLevel <= toLevel) continue;
			if (fromState == null) {
				fromState = LightEngine.QueueEntry.isFromEmptyShape(increaseData) ? Blocks.AIR.defaultBlockState() : getState(mutablePos.set(fx, fy, fz));
			}
			if (shapeOccludes(fromState, toState, direction)) continue;
			long toNode = BlockPos.asLong(tx, ty, tz);
			light.afterburner$set(toNode, newToLevel);
			if (newToLevel > 1) {
				enqueueIncrease(toNode, LightEngine.QueueEntry.increaseSkipOneDirection(newToLevel, isEmptyShape(toState), direction.getOpposite()));
			}
		}
	}

	/**
	 * @author Afterburner
	 * @reason One light data lookup per neighbor.
	 */
	@Overwrite
	@Override
	protected void propagateDecrease(long fromNode, long decreaseData) {
		LightStorageAccess light = (LightStorageAccess) storage;
		int oldFromLevel = LightEngine.QueueEntry.getFromLevel(decreaseData);
		int fx = BlockPos.getX(fromNode), fy = BlockPos.getY(fromNode), fz = BlockPos.getZ(fromNode);
		long fromSection = SectionPos.asLong(fx >> 4, fy >> 4, fz >> 4);
		for (Direction direction : PROPAGATION_DIRECTIONS) {
			if (!LightEngine.QueueEntry.shouldPropagateInDirection(decreaseData, direction)) continue;
			int tx = fx + direction.getStepX(), ty = fy + direction.getStepY(), tz = fz + direction.getStepZ();
			long toSection = ((tx ^ fx) | (ty ^ fy) | (tz ^ fz)) >>> 4 == 0 ? fromSection : SectionPos.asLong(tx >> 4, ty >> 4, tz >> 4);
			DataLayer layer = light.afterburner$layer(toSection);
			if (layer == null) continue;
			int toLevel = layer.get(tx & 15, ty & 15, tz & 15);
			if (toLevel == 0) continue;
			long toNode = BlockPos.asLong(tx, ty, tz);
			if (toLevel <= oldFromLevel - 1) {
				BlockState toState = getState(mutablePos.set(tx, ty, tz));
				int toEmission = getEmission(toNode, toState);
				light.afterburner$set(toNode, 0);
				if (toEmission < toLevel) {
					enqueueDecrease(toNode, LightEngine.QueueEntry.decreaseSkipOneDirection(toLevel, direction.getOpposite()));
				}
				if (toEmission > 0) {
					enqueueIncrease(toNode, LightEngine.QueueEntry.increaseLightFromEmission(toEmission, isEmptyShape(toState)));
				}
			} else {
				enqueueIncrease(toNode, LightEngine.QueueEntry.increaseOnlyOneDirection(toLevel, false, direction.getOpposite()));
			}
		}
	}
}
