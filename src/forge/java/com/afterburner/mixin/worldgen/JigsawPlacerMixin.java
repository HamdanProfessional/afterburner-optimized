package com.afterburner.mixin.worldgen;

import com.afterburner.worldgen.FreeSpace;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Placing a jigsaw piece's children: whether a child fits and taking it out of the room go through {@link FreeSpace}
 * instead of voxel shape joins, for the structure's room and for the room inside a piece that children attach inside.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer")
public abstract class JigsawPlacerMixin {
	@WrapOperation(method = "tryPlacingChildren", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/phys/shapes/Shapes;create(Lnet/minecraft/world/phys/AABB;)Lnet/minecraft/world/phys/shapes/VoxelShape;", ordinal = 0))
	private VoxelShape afterburner$roomInside(AABB box, Operation<VoxelShape> original) {
		return FreeSpace.inside(box, () -> original.call(box));
	}

	@WrapOperation(method = "tryPlacingChildren", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/phys/shapes/Shapes;joinIsNotEmpty(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;"
					+ "Lnet/minecraft/world/phys/shapes/BooleanOp;)Z"))
	private boolean afterburner$taken(VoxelShape room, VoxelShape tested, BooleanOp op, Operation<Boolean> original) {
		if (room instanceof FreeSpace free && op == BooleanOp.ONLY_SECOND) return !free.fits(tested);
		return original.call(FreeSpace.real(room), tested, op);
	}

	@WrapOperation(method = "tryPlacingChildren", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/phys/shapes/Shapes;joinUnoptimized(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;"
					+ "Lnet/minecraft/world/phys/shapes/BooleanOp;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
	private VoxelShape afterburner$cut(VoxelShape room, VoxelShape cut, BooleanOp op, Operation<VoxelShape> original) {
		if (room instanceof FreeSpace free && op == BooleanOp.ONLY_FIRST) return free.without(cut);
		return original.call(FreeSpace.real(room), cut, op);
	}
}
