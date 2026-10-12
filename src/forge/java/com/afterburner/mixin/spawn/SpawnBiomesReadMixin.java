package com.afterburner.mixin.spawn;

import com.afterburner.spawn.SpawnCosts;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A chunk read from disk tells {@link SpawnCosts} if one of its biomes has spawn costs. */
@Mixin(SerializableChunkData.class)
public abstract class SpawnBiomesReadMixin {
	@ModifyReturnValue(method = "read", at = @At("RETURN"))
	private ProtoChunk afterburner$read(ProtoChunk chunk, @Local(argsOnly = true) ServerLevel level) {
		SpawnCosts.arrived(level, chunk);
		return chunk;
	}
}
