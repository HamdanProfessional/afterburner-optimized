package com.afterburner.labs.worldgen;

import com.afterburner.Afterburner;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ByteOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.AbstractHugeMushroomFeature;
import net.minecraft.world.level.levelgen.feature.BambooFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.RandomBooleanSelectorFeature;
import net.minecraft.world.level.levelgen.feature.RandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.SimpleRandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.WeightedPlacedFeature;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Trees for the far land ({@link FarLand}): the game's own tree features (and huge mushrooms), run where and as the game
 * runs them (chunk by chunk, with the chunk's own decoration seed and each feature's own seed), into a stand-in world
 * whose ground is the far land's. So the trees come out where the real ones grow, of the same kinds and sizes; how high
 * they stand is as exact as the far land's ground (block by block at level 0). Only trees (and the buildings, from
 * {@link FarStructures}, put down first as the game does; and bamboo, which takes trees' places first): no grass, flowers or
 * rocks. Each chunk's trees are grown alone (as if its neighbors had none yet), so they don't depend on the order
 * chunks come in.
 * <p>
 * Growing trees costs about a millisecond a chunk, so the coarser levels (whose blocks are 2 and 4 wide) don't: there the
 * game's placement still picks where trees go and of what kind, but each tree is put down as one of a few of its kind
 * grown for real before ({@link #VARIANTS}). The trees stand where the game's would, at about the same spots and as
 * thick, but not one for one.
 */
public final class FarTrees {
	/** {@code -Dafterburner.farTrees=false}: far land without trees. */
	public static final boolean ALLOWED = !"false".equals(System.getProperty("afterburner.farTrees"));

	/** What the stand-in world's ground is: the far land being made. */
	public interface Ground {
		/** Where the ground ends (exclusive) at block x, z. */
		int top(int x, int z);

		/** The ground's top block there (before snow falls), and the ground {@code depth} blocks under that (1: right under). */
		BlockState surface(int x, int z);

		BlockState under(int x, int z, int depth);

		Holder<Biome> noiseBiome(int quartX, int quartY, int quartZ);

		/** While buildings are put down: the ground past the sheet as the generator has it (not the sheet's edge, carried on). */
		default void building(boolean on) {
		}
	}

	/** One chunk's trees: the blocks they put down (positions as {@link BlockPos#asLong}). */
	public record Grown(long[] positions, BlockState[] states) {
	}

	private final ServerLevel level;
	private final ChunkGenerator generator;
	private final long seed;
	private final int step, minY, topY, seaLevel;
	private final BlockState fluid;
	private final List<PlacedFeature> features;
	private final @Nullable FarStructures structures;
	/** Per biome: the places in {@link #features} of its trees, in order. */
	private final Map<Holder<Biome>, int[]> byBiome = new IdentityHashMap<>();
	/** Per feature (by place): how often it failed in the stand-in world; past {@link #FAILURES} it's left out. */
	private final int[] failures;
	private static final int FAILURES = 16;
	/** Per tree (by identity: they're records): a few grown for real, to put down again at the coarser levels. */
	private final Map<Feature, Variants> variants = new IdentityHashMap<>();
	private static final int VARIANTS = 8;

	private FarTrees(ServerLevel level, ChunkGenerator generator, FarLand land, BlockState fluid, int seaLevel) {
		this.level = level;
		this.generator = generator;
		this.seed = level.getSeed();
		this.minY = land.minY;
		this.topY = land.topY;
		this.seaLevel = seaLevel;
		this.fluid = fluid;
		this.step = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
		this.structures = FarStructures.of(level, generator);
		List<FeatureSorter.StepFeatureData> steps = featuresPerStep(generator);
		FeatureSorter.StepFeatureData data = step < steps.size() ? steps.get(step) : null;
		this.features = data == null ? List.of() : data.features();
		this.failures = new int[features.size()];
		if (data == null) return;
		boolean[] tree = new boolean[features.size()];
		for (int i = 0; i < tree.length; i++) {
			tree[i] = features.get(i).getFeatures().anyMatch(h -> isTree(h.value()) || h.value() instanceof BambooFeature);
			if (tree[i]) features.get(i).getFeatures().filter(h -> isTree(h.value())).forEach(h -> variants.computeIfAbsent(h.value(), f -> new Variants()));
		}
		for (Holder<Biome> biome : generator.getBiomeSource().possibleBiomes()) {
			List<HolderSet<PlacedFeature>> all = generator.getBiomeGenerationSettings(biome).features();
			if (step >= all.size()) continue;
			BitSet mine = new BitSet();
			for (Holder<PlacedFeature> f : all.get(step)) {
				int i = data.indexMapping().applyAsInt(f.value());
				if (i >= 0 && i < tree.length && tree[i]) mine.set(i);
			}
			if (!mine.isEmpty()) byBiome.put(biome, mine.stream().toArray());
		}
	}

	private static boolean isTree(Feature feature) {
		return feature instanceof TreeFeature || feature instanceof AbstractHugeMushroomFeature;
	}

	/** A tree's blocks around where it was grown: x and z offsets +-128, y up from there. */
	private record Stamp(int[] offsets, BlockState[] states) {
	}

	/** The trees of one kind grown so far (up to {@link #VARIANTS}); any thread's. */
	private static final class Variants {
		private final Stamp[] stamps = new Stamp[VARIANTS];
		private int count;

		/** One of them, or null while there aren't all yet (then the tree is grown for real and added). */
		synchronized @Nullable Stamp pick(RandomSource random) {
			return count < VARIANTS ? null : stamps[random.nextInt(VARIANTS)];
		}

		synchronized void add(Stamp stamp) {
			if (count < VARIANTS) stamps[count++] = stamp;
		}
	}

	/** Null if the generator's features can't be read (logged). */
	static @Nullable FarTrees of(ServerLevel level, ChunkGenerator generator, FarLand land, BlockState fluid, int seaLevel) {
		if (!ALLOWED) return null;
		try {
			return new FarTrees(level, generator, land, fluid, seaLevel);
		} catch (RuntimeException e) {
			Afterburner.LOGGER.error("[Afterburner] Far land: couldn't read the world's trees; the far land is made without them", e);
			return null;
		}
	}

	/** The generator's own list (the order its features' seeds come from), else worked out the same way. */
	@SuppressWarnings("unchecked")
	private static List<FeatureSorter.StepFeatureData> featuresPerStep(ChunkGenerator generator) {
		try {
			Field field = ChunkGenerator.class.getDeclaredField("featuresPerStep");
			field.setAccessible(true);
			return ((Supplier<List<FeatureSorter.StepFeatureData>>) field.get(generator)).get();
		} catch (ReflectiveOperationException | RuntimeException e) {
			return FeatureSorter.buildFeaturesPerStep(List.copyOf(generator.getBiomeSource().possibleBiomes()),
					b -> generator.getBiomeGenerationSettings(b).features(), true);
		}
	}

	/** Whether the world has any trees or buildings at all. */
	public boolean any() {
		return !byBiome.isEmpty() || structures != null;
	}

	public @Nullable FarStructures structures() {
		return structures;
	}

	public Grower grower() {
		return new Grower();
	}

	private static final Reference2ByteOpenHashMap<BlockState> SHOWS = new Reference2ByteOpenHashMap<>();

	/**
	 * Whether the far terrain shows the block (as its own kinds do: leaves, snow, full blocks and big ones; not vines,
	 * cocoa, carpets, plants).
	 */
	public static boolean shows(BlockState state) {
		synchronized (SHOWS) {
			byte known = SHOWS.getByte(state);
			if (known != 0) return known > 0;
		}
		boolean shows = shows0(state);
		synchronized (SHOWS) {
			SHOWS.put(state, (byte) (shows ? 1 : -1));
		}
		return shows;
	}

	private static boolean shows0(BlockState state) {
		if (state.isAir() || state.getBlock() instanceof LiquidBlock) return false;
		if (state.getBlock() instanceof LeavesBlock || state.is(Blocks.SNOW)) return true;
		try {
			if (state.isSolidRender()) return true;
			double volume = 0;
			for (AABB box : state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs()) {
				volume += box.getXsize() * box.getYsize() * box.getZsize();
			}
			return volume >= 0.4;
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** One thread's: its stand-in world and seeds. */
	public final class Grower {
		private final StandIn world = new StandIn();
		/** Where trees are grown alone for {@link #variants}. */
		private final StandIn nursery = new StandIn();
		private final WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
		private final FeaturePlacer placer = new FeaturePlacer(world, generator);
		private final BitSet wanted = new BitSet();
		private final ReferenceOpenHashSet<Holder<Biome>> seen = new ReferenceOpenHashSet<>();
		/** Trees grown (features placed that put something down), and chunks with buildings, for the check command. */
		public long placed, built;

		private Grower() {
		}

		/**
		 * The trees of chunk {@code cx}, {@code cz} on that ground: grown as the game grows them if {@code exact}, else put
		 * down from the few of each kind grown before; and its buildings' parts if {@code buildings} (the chunk is on the
		 * ground's own sheet: some pieces take their height from the ground the first time they're put down).
		 */
		public Grown grow(int cx, int cz, Ground ground, boolean exact, boolean buildings) {
			world.reset(ground);
			int ox = cx << 4, oz = cz << 4;
			// As the game: the trees of every biome around (it reads them off the chunks around), each sees its own.
			wanted.clear();
			seen.clear();
			for (int qz = (oz >> 2) - 1; qz <= (oz >> 2) + 4; qz++) {
				for (int qx = (ox >> 2) - 1; qx <= (ox >> 2) + 4; qx++) {
					int qy = (ground.top(qx * 4 + 2, qz * 4 + 2) - 2) >> 2;
					for (int dy = 0; dy <= 1; dy++) {
						Holder<Biome> biome = ground.noiseBiome(qx, qy + dy, qz);
						if (!seen.add(biome)) continue;
						int[] mine = byBiome.get(biome);
						if (mine != null) for (int i : mine) wanted.set(i);
					}
				}
			}
			List<FarStructures.Placed> touching = structures == null || !buildings ? List.of() : structures.touching(cx, cz);
			if (wanted.isEmpty() && touching.isEmpty()) return new Grown(new long[0], new BlockState[0]);
			long decorationSeed = random.setDecorationSeed(seed, ox, oz);
			// The buildings first, as the game (their step comes before the trees').
			if (!touching.isEmpty()) {
				ground.building(true);
				try {
					structures.place(world, random, decorationSeed, cx, cz, touching);
				} finally {
					ground.building(false);
				}
				built++;
			}
			BlockPos origin = new BlockPos(ox, level.getMinSectionY() << 4, oz);
			for (int i = wanted.nextSetBit(0); i >= 0; i = wanted.nextSetBit(i + 1)) {
				if (failures[i] >= FAILURES) continue;
				random.setFeatureSeed(decorationSeed, i, step);
				try {
					if (exact ? placer.placeWithBiomeCheck(features.get(i), random, origin) : placeStamped(features.get(i), origin, true)) placed++;
				} catch (RuntimeException e) {
					// What it put down before failing stays (the chunk's other trees aren't grown, as in a failed chunk).
					if (failures[i]++ == 0) Afterburner.LOGGER.warn("[Afterburner] Far land: a tree feature failed in the far land: {}", features.get(i), e);
					if (failures[i] == FAILURES) Afterburner.LOGGER.warn("[Afterburner] Far land: that tree feature is left out from now on");
				}
			}
			return world.grown();
		}

		/**
		 * As the game's {@link FeaturePlacer}: the placement's positions depth first (each goes all the way down before
		 * the next is drawn), but with trees put down by {@link #putDown}.
		 */
		private boolean placeStamped(PlacedFeature placed, BlockPos origin, boolean biomeCheck) {
			Feature feature = placed.feature().value();
			List<PlacementModifier> placement = placed.placement();
			if (placement.isEmpty()) return putDown(feature, origin);
			PlacementContext context = new PlacementContext(world, generator, biomeCheck ? Optional.of(placed) : Optional.empty());
			List<BlockPos> positions = new ArrayList<>(), modified = new ArrayList<>();
			IntArrayList indices = new IntArrayList();
			positions.add(origin);
			indices.add(0);
			boolean any = false;
			while (!positions.isEmpty()) {
				BlockPos pos = positions.removeLast();
				int index = indices.removeInt(indices.size() - 1);
				modified.clear();
				placement.get(index).modify(context, random, pos, modified::add);
				if (index + 1 < placement.size()) {
					for (int j = modified.size() - 1; j >= 0; j--) {
						positions.add(modified.get(j));
						indices.add(index + 1);
					}
				} else {
					for (BlockPos next : modified) any |= putDown(feature, next);
				}
			}
			return any;
		}

		/** The game's choosing between features as it does; a tree as one of its kind grown before; anything else as is. */
		private boolean putDown(Feature feature, BlockPos pos) {
			if (feature instanceof RandomSelectorFeature selector) {
				for (WeightedPlacedFeature option : selector.features()) {
					if (random.nextFloat() < option.chance()) return placeStamped(option.feature().value(), pos, false);
				}
				return placeStamped(selector.defaultFeature().value(), pos, false);
			}
			if (feature instanceof RandomBooleanSelectorFeature selector) {
				return placeStamped((random.nextBoolean() ? selector.featureTrue() : selector.featureFalse()).value(), pos, false);
			}
			if (feature instanceof SimpleRandomSelectorFeature selector) {
				return placeStamped(selector.features().get(random.nextInt(selector.features().size())).value(), pos, false);
			}
			Variants kind = variants.get(feature);
			if (kind == null) return feature.place(world, generator, random, pos);
			Stamp stamp = kind.pick(random);
			if (stamp == null) {
				nursery.reset(world.ground);
				if (!feature.place(nursery, generator, random, pos)) return false;
				stamp = nursery.stamp(pos);
				kind.add(stamp);
			}
			world.put(stamp, pos);
			return true;
		}
	}

	/**
	 * The world the trees grow into: the far land's ground and the sea, and what they put down. Nothing else is there: no
	 * entities, light, block entities, chunks or ticks.
	 */
	private final class StandIn implements WorldGenLevel {
		private final Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
		/** Per column ({@link #column}): one above the highest block put down. */
		private final Long2IntOpenHashMap tops = new Long2IntOpenHashMap();
		private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		private final RandomSource random = new XoroshiroRandomSource(0L);
		private final BiomeManager biomes = new BiomeManager(this, BiomeManager.obfuscateSeed(seed));
		private Ground ground;
		/** What {@link #getChunk} gives: blocks marked there to be updated later (fallen logs do) are let go. */
		private @Nullable ProtoChunk marks;

		StandIn() {
			tops.defaultReturnValue(Integer.MIN_VALUE);
		}

		void reset(Ground ground) {
			this.ground = ground;
			blocks.clear();
			tops.clear();
			if (marks != null) {
				for (ShortList list : marks.getPostProcessing()) {
					if (list != null) list.clear();
				}
			}
		}

		/** What's been put down, around {@code at} (only what the far terrain shows, and nothing under {@code at}). */
		Stamp stamp(BlockPos at) {
			IntArrayList offsets = new IntArrayList();
			List<BlockState> states = new ArrayList<>();
			for (Long2ObjectOpenHashMap.Entry<BlockState> e : blocks.long2ObjectEntrySet()) {
				long p = e.getLongKey();
				int dx = BlockPos.getX(p) - at.getX(), dy = BlockPos.getY(p) - at.getY(), dz = BlockPos.getZ(p) - at.getZ();
				if (dy < 0 || dy >= 1 << 15 || Math.abs(dx) >= 128 || Math.abs(dz) >= 128 || !shows(e.getValue())) continue;
				offsets.add(dy << 16 | (dz + 128) << 8 | dx + 128);
				states.add(e.getValue());
			}
			return new Stamp(offsets.toIntArray(), states.toArray(BlockState[]::new));
		}

		void put(Stamp stamp, BlockPos at) {
			int[] offsets = stamp.offsets();
			BlockState[] states = stamp.states();
			for (int i = 0; i < offsets.length; i++) {
				int o = offsets[i];
				setBlock(this.at.set(at.getX() + (o & 0xFF) - 128, at.getY() + (o >>> 16), at.getZ() + (o >>> 8 & 0xFF) - 128), states[i], 2, 512);
			}
		}

		Grown grown() {
			long[] positions = new long[blocks.size()];
			BlockState[] states = new BlockState[blocks.size()];
			int n = 0;
			for (Long2ObjectOpenHashMap.Entry<BlockState> e : blocks.long2ObjectEntrySet()) {
				positions[n] = e.getLongKey();
				states[n++] = e.getValue();
			}
			return new Grown(positions, states);
		}

		private static long column(int x, int z) {
			return (long) x << 32 | z & 0xFFFFFFFFL;
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			BlockState placed = blocks.get(pos.asLong());
			if (placed != null) return placed;
			int y = pos.getY();
			if (y < minY || y >= topY) return Blocks.VOID_AIR.defaultBlockState();
			int top = ground.top(pos.getX(), pos.getZ());
			if (y < top) return y == top - 1 ? ground.surface(pos.getX(), pos.getZ()) : ground.under(pos.getX(), pos.getZ(), top - 1 - y);
			return y < seaLevel ? fluid : Blocks.AIR.defaultBlockState();
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			return getBlockState(pos).getFluidState();
		}

		@Override
		public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
			return predicate.test(getBlockState(pos));
		}

		@Override
		public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
			return predicate.test(getFluidState(pos));
		}

		@Override
		public boolean setBlock(BlockPos pos, BlockState state, int flags, int updateLimit) {
			int y = pos.getY();
			if (y < minY || y >= topY) return false;
			blocks.put(pos.asLong(), state);
			long column = column(pos.getX(), pos.getZ());
			if (y + 1 > tops.get(column)) tops.put(column, y + 1);
			return true;
		}

		@Override
		public boolean removeBlock(BlockPos pos, boolean movedByPiston) {
			return setBlock(pos, getFluidState(pos).createLegacyBlock(), 3, 512);
		}

		@Override
		public boolean destroyBlock(BlockPos pos, boolean dropResources, @Nullable Entity breaker, int updateLimit) {
			return removeBlock(pos, false);
		}

		@Override
		public int getHeight(Heightmap.Types type, int x, int z) {
			int top = ground.top(x, z);
			if (top <= minY) return minY;
			Predicate<BlockState> opaque = type.isOpaque();
			int from = Math.max(Math.max(top, seaLevel), tops.get(column(x, z)));
			for (int y = Math.min(from, topY) - 1; y >= top; y--) {
				if (opaque.test(getBlockState(at.set(x, y, z)))) return y + 1;
			}
			return top;
		}

		@Override
		public Holder<Biome> getUncachedNoiseBiome(int quartX, int quartY, int quartZ) {
			return ground.noiseBiome(quartX, quartY, quartZ);
		}

		@Override
		public BiomeManager getBiomeManager() {
			return biomes;
		}

		@Override
		public @Nullable ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean loadOrGenerate) {
			if (!loadOrGenerate) return null;
			// No chunks: only one to take the marks for blocks to update later (the far land has no later).
			if (marks == null) marks = new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
			return marks;
		}

		@Override
		public long getSeed() {
			return seed;
		}

		@Override
		public int getSeaLevel() {
			return seaLevel;
		}

		@Override
		public DimensionType dimensionType() {
			return level.dimensionType();
		}

		@Override
		public RegistryAccess registryAccess() {
			return level.registryAccess();
		}

		@Override
		public FeatureFlagSet enabledFeatures() {
			return level.enabledFeatures();
		}

		@Override
		public EnvironmentAttributeReader environmentAttributes() {
			return level.environmentAttributes();
		}

		@Override
		public ServerLevel getLevel() {
			return level;
		}

		@Override
		public @Nullable MinecraftServer getServer() {
			return level.getServer();
		}

		@Override
		public LevelData getLevelData() {
			return level.getLevelData();
		}

		@Override
		public ChunkSource getChunkSource() {
			return level.getChunkSource();
		}

		@Override
		public WorldBorder getWorldBorder() {
			return level.getWorldBorder();
		}

		@Override
		public RandomSource getRandom() {
			return random;
		}

		@Override
		public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
			return new DifficultyInstance(Difficulty.NORMAL, 0L, 0L, 0.0F);
		}

		@Override
		public LevelLightEngine getLightEngine() {
			throw new UnsupportedOperationException("Far trees have no light");
		}

		/** No light engine: full sky light over everything, a little less under what's been put down, none in the ground. */
		private int sky(BlockPos pos) {
			if (pos.getY() < ground.top(pos.getX(), pos.getZ())) return 0;
			return pos.getY() >= tops.get(column(pos.getX(), pos.getZ())) ? 15 : 12;
		}

		@Override
		public int getBrightness(LightLayer layer, BlockPos pos) {
			return layer == LightLayer.SKY ? sky(pos) : 0;
		}

		@Override
		public int getRawBrightness(BlockPos pos, int darkening) {
			return Math.max(0, sky(pos) - darkening);
		}

		@Override
		public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
			return null;
		}

		@Override
		public LevelTickAccess<Block> getBlockTicks() {
			return BlackholeTickAccess.emptyLevelList();
		}

		@Override
		public LevelTickAccess<Fluid> getFluidTicks() {
			return BlackholeTickAccess.emptyLevelList();
		}

		@Override
		public long nextSubTickCount() {
			return 0L;
		}

		@Override
		public int getSkyDarken() {
			return 0;
		}

		@Override
		public boolean isClientSide() {
			return false;
		}

		@Override
		public List<? extends Player> players() {
			return List.of();
		}

		@Override
		public List<Entity> getEntities(@Nullable Entity except, AABB bb, Predicate<? super Entity> selector) {
			return List.of();
		}

		@Override
		public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> type, AABB bb, Predicate<? super T> selector) {
			return List.of();
		}

		@Override
		public void playSound(@Nullable Entity except, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {
		}

		@Override
		public void addParticle(ParticleOptions particle, double x, double y, double z, double xd, double yd, double zd) {
		}

		@Override
		public void levelEvent(@Nullable Entity source, int type, BlockPos pos, int data) {
		}

		@Override
		public void gameEvent(Holder<GameEvent> gameEvent, Vec3 position, GameEvent.Context context) {
		}
	}
}
