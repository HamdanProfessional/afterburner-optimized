package com.afterburner.mixin.spawn;

import com.afterburner.spawn.SpawnLookups;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/** Natural spawning without lookups whose answer is known, see {@link SpawnLookups}. */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
	private static final String SPAWN_AT = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;"
			+ "Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;"
			+ "Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V";

	@Inject(method = SPAWN_AT, at = {@At("HEAD"), @At("RETURN")})
	private static void afterburner$forget(CallbackInfo ci) {
		SpawnLookups.forget();
	}

	/** The biome is only looked at for fish (fewer in some biomes); which mobs may spawn comes from a lookup of its own. */
	@WrapOperation(method = "getRandomSpawnMobAt", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/level/ServerLevel;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"))
	private static Holder<Biome> afterburner$biomeForFish(ServerLevel level, BlockPos pos, Operation<Holder<Biome>> original,
			@Local(argsOnly = true) MobCategory category) {
		return category == MobCategory.WATER_AMBIENT ? original.call(level, pos) : null;
	}

	@ModifyReturnValue(method = "getRandomSpawnMobAt", at = @At("RETURN"))
	private static Optional<MobSpawnSettings.SpawnerData> afterburner$picked(Optional<MobSpawnSettings.SpawnerData> picked,
			@Local(argsOnly = true) ServerLevel level, @Local(argsOnly = true) MobCategory category, @Local(argsOnly = true) BlockPos pos) {
		if (picked.isPresent()) SpawnLookups.picked(level, category, picked.get(), pos);
		return picked;
	}

	@WrapOperation(method = "isValidSpawnPostitionForType", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/NaturalSpawner;canSpawnMobAt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/StructureManager;"
					+ "Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/entity/MobCategory;"
					+ "Lnet/minecraft/world/level/biome/MobSpawnSettings$SpawnerData;Lnet/minecraft/core/BlockPos;)Z"))
	private static boolean afterburner$mayStay(ServerLevel level, StructureManager structures, ChunkGenerator generator, MobCategory category,
			MobSpawnSettings.SpawnerData data, BlockPos pos, Operation<Boolean> original) {
		if (!SpawnLookups.justPicked(level, category, data, pos)) return original.call(level, structures, generator, category, data, pos);
		if (SpawnLookups.CHECK) SpawnLookups.check(original.call(level, structures, generator, category, data, pos));
		return true;
	}
}
