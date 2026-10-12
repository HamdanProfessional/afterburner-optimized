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
			Map.entry("BatchedSectionMixin", Features.CHUNK_BATCHING),
			Map.entry("ChunkBatchingMixin", Features.CHUNK_BATCHING),
			Map.entry("CompileTaskAccessor", Features.CHUNK_BATCHING),
			Map.entry("MeshStampMixin", Features.CHUNK_BATCHING),
			Map.entry("RebuildStampMixin", Features.CHUNK_BATCHING),
			Map.entry("ResortStampMixin", Features.CHUNK_BATCHING),
			Map.entry("ResultStampMixin", Features.CHUNK_BATCHING),
			Map.entry("SectionUploadMixin", Features.CHUNK_BATCHING),
			Map.entry("VertexBufferTagMixin", Features.CHUNK_BATCHING),
			Map.entry("CompactShaderMixin", Features.CHUNK_BATCHING),
			Map.entry("BufferBuilderAccessor", Features.CHUNK_BATCHING),
			Map.entry("TerrainAtlasMixin", Features.CHUNK_BATCHING),
			Map.entry("TerrainBlocksMixin", Features.CHUNK_BATCHING),
			Map.entry("TerrainMeshMixin", Features.CHUNK_BATCHING),
			Map.entry("LeafCullingMixin", Features.LEAF_CULLING),
			Map.entry("LightMemoMixin", Features.SMOOTH_LIGHT_CACHE),
			Map.entry("LightMemoCompileMixin", Features.SMOOTH_LIGHT_CACHE),
			Map.entry("ModelBlockRendererAccessor", Features.SMOOTH_LIGHT_CACHE),
			Map.entry("BiomeBlendMixin", Features.FAST_BIOME_BLEND),
			Map.entry("BiomeMemoCompileMixin", Features.FAST_BIOME_BLEND),
			Map.entry("OcclusionRayMixin", Features.FAST_VISIBILITY),
			Map.entry("FrustumPassMixin", Features.FAST_VISIBILITY),
			Map.entry("GraphStorageMixin", Features.FAST_VISIBILITY),
			Map.entry("SectionMoveMixin", Features.FAST_VISIBILITY),
			Map.entry("LightSwitchMixin", Features.FAST_VISIBILITY),
			Map.entry("SectionCountMixin", Features.FAST_VISIBILITY),
			Map.entry("SectionMarkMixin", Features.FAST_VISIBILITY),
			Map.entry("SectionScanMixin", Features.FAST_VISIBILITY),
			Map.entry("OffscreenBuildMixin", Features.OFFSCREEN_BUILDS),
			Map.entry("OffscreenSectionMixin", Features.OFFSCREEN_BUILDS),
			Map.entry("AnimatedAtlasMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("AnimatedMeshMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("AnimatedScanMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("AnimatedSpriteMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("AnimatedVisibleMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("AnimationSkipMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("AnimationTickMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("SeenBlockModelMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("SeenFlameMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("SeenGuiSpriteMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("SeenItemMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("SeenScreenEffectMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("OcclusionSectionMixin", Features.OCCLUSION_CULLING),
			Map.entry("EntityCullingMixin", Features.ENTITY_CULLING),
			Map.entry("CullingPickMixin", Features.ENTITY_CULLING),
			Map.entry("EntityOcclusionMixin", Features.ENTITY_CULLING),
			Map.entry("BlockEntityOcclusionMixin", Features.ENTITY_CULLING),
			Map.entry("ParticleAccessor", Features.FAST_PARTICLES),
			Map.entry("FrustumInvoker", Features.FAST_PARTICLES),
			Map.entry("ParticleViewAccessor", Features.FAST_PARTICLES),
			Map.entry("ParticleFrameMixin", Features.FAST_PARTICLES),
			Map.entry("ParticleCullingMixin", Features.FAST_PARTICLES),
			Map.entry("SeenSectionMixin", Features.FAST_PARTICLES),
			Map.entry("ParticleLightMixin", Features.FAST_PARTICLES),
			Map.entry("ChunkEpochMixin", Features.FAST_PARTICLES),
			Map.entry("LightEpochMixin", Features.FAST_PARTICLES),
			Map.entry("TranslucentCentersMixin", Features.TRANSLUCENT_CULLING),
			Map.entry("TranslucentFacingsMixin", Features.TRANSLUCENT_CULLING),
			Map.entry("TranslucentSortMixin", Features.TRANSLUCENT_CULLING),
			Map.entry("TranslucentViewMixin", Features.TRANSLUCENT_CULLING),
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
		// enabled() is already off when the one it is built on (needs()) is.
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
