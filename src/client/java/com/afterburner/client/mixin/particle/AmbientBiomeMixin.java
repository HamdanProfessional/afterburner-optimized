package com.afterburner.client.mixin.particle;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The biome of each random block the game looks at for ambient particles (about 1300 a tick): the biome of its 4x4x4 cell,
 * one lookup, instead of the blurred one between neighboring cells (eight). Only where the particles are made differs, by
 * a few blocks near a biome's edge.
 */
@Mixin(ClientLevel.class)
public class AmbientBiomeMixin {
	@WrapOperation(method = "doAnimateTick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"))
	private Holder<Biome> afterburner$cellBiome(ClientLevel level, BlockPos pos, Operation<Holder<Biome>> original) {
		return level.getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()), QuartPos.fromBlock(pos.getZ()));
	}
}
