package com.afterburner;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Skips the mixins of optimizations that are turned off (see {@link Features}). */
public class AfterburnerMixinPlugin implements IMixinConfigPlugin {
	private static final Map<String, Features> MIXINS = Map.ofEntries(
			Map.entry("ChunkStepMixin", Features.PARALLEL_WORLDGEN),
			Map.entry("StructureStartMixin", Features.PARALLEL_WORLDGEN),
			Map.entry("BlockLightEngineMixin", Features.FAST_LIGHT),
			Map.entry("DataLayerStorageMapMixin", Features.FAST_LIGHT),
			Map.entry("LayerLightSectionStorageMixin", Features.FAST_LIGHT),
			Map.entry("LightEngineMixin", Features.FAST_LIGHT),
			Map.entry("SkyLightEngineMixin", Features.FAST_LIGHT),
			Map.entry("BlockCollisionsMixin", Features.FAST_COLLISIONS),
			Map.entry("VoxelShapeMixin", Features.FAST_COLLISIONS),
			Map.entry("EntityCollisionsMixin", Features.FAST_COLLISIONS),
			Map.entry("EntitySectionMixin", Features.FAST_COLLISIONS),
			Map.entry("EntitySectionStorageMixin", Features.FAST_ENTITY_SEARCH),
			Map.entry("RandomTickSectionMixin", Features.FAST_CHUNK_TICKS),
			Map.entry("RandomTickChunkMixin", Features.FAST_CHUNK_TICKS),
			Map.entry("RandomTickLevelMixin", Features.FAST_CHUNK_TICKS),
			Map.entry("BrainMixin", Features.FAST_MOB_AI),
			Map.entry("GateBehaviorMixin", Features.FAST_MOB_AI),
			Map.entry("MoveToBlockGoalMixin", Features.FAST_MOB_AI),
			Map.entry("PoiManagerMixin", Features.FAST_MOB_AI),
			Map.entry("SectionStorageMixin", Features.FAST_MOB_AI),
			Map.entry("GoalSelectorMixin", Features.FAST_MOB_TICKS),
			Map.entry("MobTicksLivingMixin", Features.FAST_MOB_TICKS),
			Map.entry("TrackingRangeMixin", Features.FAST_MOB_TICKS),
			Map.entry("HopperSectionMixin", Features.FAST_HOPPERS),
			Map.entry("HopperSearchMixin", Features.FAST_HOPPERS),
			Map.entry("NaturalSpawnerMixin", Features.FAST_SPAWNING),
			Map.entry("SpawnCostMixin", Features.FAST_SPAWNING),
			Map.entry("SpawnCostAccessor", Features.FAST_SPAWNING),
			Map.entry("SpawnBiomesMixin", Features.FAST_SPAWNING),
			Map.entry("SpawnBiomesReadMixin", Features.FAST_SPAWNING),
			Map.entry("JigsawPlacementMixin", Features.FAST_STRUCTURES),
			Map.entry("JigsawPlacerMixin", Features.FAST_STRUCTURES),
			Map.entry("BulkSectionAccessMixin", Features.FAST_ORES),
			Map.entry("ClimateLeafMixin", Features.FAST_BIOME_SEARCH),
			Map.entry("ClimateListMixin", Features.FAST_BIOME_SEARCH),
			Map.entry("LevelTicksMixin", Features.PARKED_TICKS),
			Map.entry("ServerLevelTicksMixin", Features.PARKED_TICKS),
			Map.entry("ChunkMapTicksMixin", Features.PARKED_TICKS),
			Map.entry("DistanceManagerAccessor", Features.PARKED_TICKS),
			Map.entry("SimulationTrackerMixin", Features.PARKED_TICKS),
			Map.entry("EntityLoadMixin", Features.PARKED_TICKS),
			Map.entry("BlockEntitySleepMixin", Features.IDLE_BLOCK_ENTITIES),
			Map.entry("FurnaceSleepMixin", Features.IDLE_BLOCK_ENTITIES),
			Map.entry("CampfireSleepMixin", Features.IDLE_BLOCK_ENTITIES),
			Map.entry("SleepingTickerMixin", Features.IDLE_BLOCK_ENTITIES),
			Map.entry("ExplosionMixin", Features.FAST_EXPLOSIONS),
			Map.entry("EntityCalculatorAccessor", Features.FAST_EXPLOSIONS),
			Map.entry("WireEvaluatorMixin", Features.FAST_REDSTONE),
			Map.entry("WireSignalMixin", Features.FAST_REDSTONE),
			Map.entry("RedstoneWireAccessor", Features.FAST_REDSTONE),
			Map.entry("PalettedContainerMixin", Features.LEAN_SECTIONS),
			Map.entry("BlockShapesMixin", Features.SHARED_SHAPES),
			Map.entry("VoxelShapeAccessor", Features.SHARED_SHAPES),
			Map.entry("BitSetShapeAccessor", Features.SHARED_SHAPES),
			Map.entry("WorkerPriorityMixin", Features.WORKER_PRIORITY));

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		// The FPS target's shorter draw distance cuts vanilla's section lists, which Sodium doesn't use: its fog would come in
		// with nothing left out, so both stay out with Sodium.
		if ((name.equals("FpsTargetExtractMixin") || name.equals("FpsTargetFogMixin")) && FabricLoader.getInstance().isModLoaded("sodium")) {
			return false;
		}
		Features feature = MIXINS.get(name);
		return feature == null || feature.enabled();
	}

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
