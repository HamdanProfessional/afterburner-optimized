package com.afterburner;

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
			Map.entry("CompiledSectionMeshMixin", Features.FACE_CULLING),
			Map.entry("LevelRendererMixin", Features.FACE_CULLING),
			Map.entry("TranslucencyPointOfViewAccessor", Features.FACE_CULLING),
			Map.entry("DrawPrepMixin", Features.DRAW_PREP),
			Map.entry("ChunkBatchingMixin", Features.CHUNK_BATCHING),
			Map.entry("DrawSeparateMixin", Features.CHUNK_BATCHING),
			Map.entry("RegionMeshMixin", Features.CHUNK_BATCHING),
			Map.entry("SectionCompilerMixin", Features.CHUNK_BATCHING),
			Map.entry("SectionResultsMixin", Features.CHUNK_BATCHING),
			Map.entry("SliceCacheMixin", Features.CHUNK_BATCHING),
			Map.entry("HeapAffinityMixin", Features.CHUNK_BATCHING),
			Map.entry("SectionChangeMixin", Features.CHUNK_BATCHING),
			Map.entry("WideSectionsMixin", Features.CHUNK_BATCHING),
			// Terrain for shader packs, which only the chunk batcher draws.
			Map.entry("TerrainBlocksMixin", Features.CHUNK_BATCHING),
			Map.entry("TerrainAtlasMixin", Features.CHUNK_BATCHING),
			Map.entry("BufferBuilderAccessor", Features.CHUNK_BATCHING),
			// Uploads compact vertices, and terrain for shader packs.
			Map.entry("CompactUploadMixin", Features.CHUNK_BATCHING),
			// Needs the mesh changes the chunk batcher hears of.
			Map.entry("BlockEntityScanMixin", Features.CHUNK_BATCHING),
			Map.entry("OffscreenBuildMixin", Features.OFFSCREEN_BUILDS),
			Map.entry("OffscreenSectionMixin", Features.OFFSCREEN_BUILDS),
			Map.entry("OcclusionSectionMixin", Features.OCCLUSION_CULLING),
			Map.entry("OcclusionProjectionMixin", Features.OCCLUSION_CULLING),
			Map.entry("EntityCullingMixin", Features.ENTITY_CULLING),
			Map.entry("BlockEntityCullingMixin", Features.ENTITY_CULLING),
			Map.entry("CullingExtractMixin", Features.ENTITY_CULLING),
			Map.entry("EntityOcclusionMixin", Features.ENTITY_CULLING),
			Map.entry("BlockEntityOcclusionMixin", Features.ENTITY_CULLING),
			Map.entry("TranslucentCentersMixin", Features.TRANSLUCENT_CULLING),
			Map.entry("TranslucentFacingsMixin", Features.TRANSLUCENT_CULLING),
			Map.entry("TranslucentMeshMixin", Features.TRANSLUCENT_CULLING),
			Map.entry("TranslucentSortMixin", Features.TRANSLUCENT_CULLING),
			Map.entry("TranslucentUploadMixin", Features.TRANSLUCENT_CULLING),
			Map.entry("FilteringShortcutMixin", Features.FAST_FILTERING),
			Map.entry("LeafCullingMixin", Features.LEAF_CULLING),
			Map.entry("LeafCullingIndigoMixin", Features.LEAF_CULLING),
			Map.entry("BiomeBlendMixin", Features.FAST_BIOME_BLEND),
			Map.entry("BiomeMemoCompileMixin", Features.FAST_BIOME_BLEND),
			Map.entry("LightMemoMixin", Features.SMOOTH_LIGHT_CACHE),
			Map.entry("LightMemoCompileMixin", Features.SMOOTH_LIGHT_CACHE),
			Map.entry("BlockModelLighterAccessor", Features.SMOOTH_LIGHT_CACHE),
			Map.entry("OcclusionRayMixin", Features.FAST_VISIBILITY),
			Map.entry("SectionToNodeMapAccessor", Features.FAST_VISIBILITY),
			Map.entry("ViewAreaAccessor", Features.FAST_VISIBILITY),
			Map.entry("AnimatedSpriteMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("AnimationSkipMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("AnimatedAtlasMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("AnimationTickMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("AnimatedScanMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("AnimatedResultsMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("AnimatedMeshMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("SeenItemMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("SeenBlockModelMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("SeenMovingBlockMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("SeenFlameMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("SeenScreenEffectMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("SeenOverlayMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("SeenGuiSpriteMixin", Features.VISIBLE_ANIMATIONS),
			Map.entry("VertexAlignMixin", Features.COMPACT_VERTICES),
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
			Map.entry("TicketStorageMixin", Features.FAST_CHUNK_TICKS),
			Map.entry("BrainMixin", Features.FAST_MOB_AI),
			Map.entry("GateBehaviorMixin", Features.FAST_MOB_AI),
			Map.entry("MoveToBlockGoalMixin", Features.FAST_MOB_AI),
			Map.entry("PoiManagerMixin", Features.FAST_MOB_AI),
			Map.entry("SectionStorageMixin", Features.FAST_MOB_AI),
			Map.entry("GoalSelectorMixin", Features.FAST_MOB_TICKS),
			Map.entry("MobTicksEntityMixin", Features.FAST_MOB_TICKS),
			Map.entry("MobTicksLivingMixin", Features.FAST_MOB_TICKS),
			Map.entry("TrackingRangeMixin", Features.FAST_MOB_TICKS),
			Map.entry("HopperSectionMixin", Features.FAST_HOPPERS),
			Map.entry("HopperSearchMixin", Features.FAST_HOPPERS),
			Map.entry("NaturalSpawnerMixin", Features.FAST_SPAWNING),
			Map.entry("SpawnCostMixin", Features.FAST_SPAWNING),
			Map.entry("SpawnStateMixin", Features.FAST_SPAWNING),
			Map.entry("AttributeSystemMixin", Features.FAST_SPAWNING),
			Map.entry("AttributeSamplerAccessor", Features.FAST_SPAWNING),
			Map.entry("SpawnBiomesMixin", Features.FAST_SPAWNING),
			Map.entry("SpawnBiomesReadMixin", Features.FAST_SPAWNING),
			Map.entry("JigsawPlacementMixin", Features.FAST_STRUCTURES),
			Map.entry("JigsawPlacerMixin", Features.FAST_STRUCTURES),
			Map.entry("BulkSectionAccessMixin", Features.FAST_ORES),
			Map.entry("OreVeinShareMixin", Features.FAST_ORES),
			Map.entry("ClimateLeafMixin", Features.FAST_BIOME_SEARCH),
			Map.entry("ClimateListMixin", Features.FAST_BIOME_SEARCH),
			Map.entry("LevelTicksMixin", Features.PARKED_TICKS),
			Map.entry("ServerLevelTicksMixin", Features.PARKED_TICKS),
			Map.entry("ChunkMapTicksMixin", Features.PARKED_TICKS),
			Map.entry("DistanceManagerAccessor", Features.PARKED_TICKS),
			Map.entry("SimulationTrackerMixin", Features.PARKED_TICKS),
			Map.entry("EntityLoadMixin", Features.PARKED_TICKS),
			Map.entry("PalettedContainerMixin", Features.LEAN_SECTIONS),
			Map.entry("UberGpuBufferMixin", Features.CHUNK_BUFFERS),
			Map.entry("BuildCopyMixin", Features.BUILD_COPIES),
			Map.entry("WorkerPriorityMixin", Features.WORKER_PRIORITY));

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		Features feature = MIXINS.get(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
		if ((feature == Features.COMPACT_VERTICES || feature == Features.OCCLUSION_CULLING || feature == Features.ENTITY_CULLING
				|| feature == Features.TRANSLUCENT_CULLING || feature == Features.VISIBLE_ANIMATIONS)
				&& !Features.CHUNK_BATCHING.enabled()) {
			return false;
		}
		if (feature == Features.ENTITY_CULLING && !Features.OCCLUSION_CULLING.enabled()) return false;
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
