package com.afterburner.mixin.spawn;

import com.afterburner.spawn.SpawnCosts;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A chunk given biomes (new land, /fillbiome) tells {@link SpawnCosts} if one of them has spawn costs. */
@Mixin(ChunkAccess.class)
public abstract class SpawnBiomesMixin {
	@Shadow
	@Final
	protected LevelHeightAccessor levelHeightAccessor;

	@Inject(method = "fillBiomesFromNoise", at = @At("TAIL"))
	private void afterburner$biomesGiven(CallbackInfo ci) {
		if (levelHeightAccessor instanceof ServerLevel level) SpawnCosts.arrived(level, (ChunkAccess) (Object) this);
	}
}
