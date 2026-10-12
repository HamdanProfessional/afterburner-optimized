package com.afterburner.mixin.memory;

import com.afterburner.memory.SharedShapes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Function;

/** The shapes a block works out for each of its states are shared with other blocks' ({@link SharedShapes}). */
@Mixin(Block.class)
public class BlockShapesMixin {
	@ModifyVariable(method = "getShapeForEachState", at = @At("HEAD"), argsOnly = true)
	private Function<BlockState, VoxelShape> afterburner$share(Function<BlockState, VoxelShape> shapeCalculator) {
		return state -> SharedShapes.share(shapeCalculator.apply(state));
	}
}
