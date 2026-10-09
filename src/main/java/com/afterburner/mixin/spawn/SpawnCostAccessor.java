package com.afterburner.mixin.spawn;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.MobSpawnSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** The spawn costs a biome's settings hold. For {@link com.afterburner.spawn.SpawnCosts}. */
@Mixin(MobSpawnSettings.class)
public interface SpawnCostAccessor {
	@Accessor("mobSpawnCosts")
	Map<EntityType<?>, ?> afterburner$costs();
}
