package com.afterburner.client.mixin.render;

import com.afterburner.client.render.BiomeMemo;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The biome blend of block colours takes its biomes from {@link BiomeMemo} while a chunk section is being built. */
@Mixin(ClientLevel.class)
public class BiomeBlendMixin {
	@WrapOperation(method = "calculateBlockTint", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"))
	private Holder<Biome> afterburner$rememberedBiome(ClientLevel level, BlockPos pos, Operation<Holder<Biome>> original) {
		return BiomeMemo.biome(level, pos, original);
	}
}
