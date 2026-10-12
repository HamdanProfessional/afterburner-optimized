package com.afterburner.mixin.worldgen;

import com.afterburner.worldgen.FreeSpace;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The room a jigsaw structure may grow into starts as a {@link FreeSpace}: its outer box with the first piece taken out. */
@Mixin(JigsawPlacement.class)
public abstract class JigsawPlacementMixin {
	@WrapOperation(method = "lambda$addPieces$2", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/phys/shapes/Shapes;join(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;"
					+ "Lnet/minecraft/world/phys/shapes/BooleanOp;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
	private static VoxelShape afterburner$room(VoxelShape outer, VoxelShape start, BooleanOp op, Operation<VoxelShape> original) {
		if (op != BooleanOp.ONLY_FIRST) return original.call(outer, start, op);
		return FreeSpace.around(outer, start, () -> original.call(outer, start, op));
	}
}
