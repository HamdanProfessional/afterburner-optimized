package com.afterburner.labs.worldgen;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityBufferPool;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The land a world's generator would make, worked out from its seed without making the chunks: for each column the
 * ground's height, its biome, the blocks its surface rules put on top (and down the side of a cliff), the sea, snow and
 * ice, and air under the ground where it's high (floating islands, arches) and the ground under that; up to level
 * {@link #TREE_LEVELS}, its trees ({@link FarTrees}) and the buildings over the ground ({@link FarStructures}). No caves.
 * It's for the far terrain where no chunk was ever seen, so it comes in the far terrain's own form: 16 x 16 columns of
 * voxels 2^level blocks wide and tall.
 * <p>
 * The ground comes from the generator's own final density, so it's where the real chunks' ground will be: at levels 0
 * and 1 exactly (sampled block by block, the same way the generator does), further out at the corners of its noise
 * cells, from which the height between them is worked out. Up to level {@link #TREE_LEVELS} it's shaped to the buildings
 * as the game shapes it ({@link FarStructures#shaping}). Any thread, one {@link Sampler} per thread.
 */
public final class FarLand {
	/** Bumped when what's made changes, so land made by an older version is made again: 1 to 127. */
	public static final int VERSION = 4;
	/** Palette places with a fixed meaning: air, and solid ground nothing sees. */
	public static final int AIR = 0, HIDDEN = 1;
	/** Surface rules are asked this many blocks down a cliff at most; below that it's the plain ground block. */
	private static final int RULE_DEPTH = 48;
	/** The ground is looked for from this far below the preliminary surface to this far above it, at first. */
	private static final int BELOW = 24, ABOVE = 40, MORE = 48;
	/**
	 * Levels 2 and up: a column's search starts this far above its preliminary surface (the ground is 0 to 50 blocks
	 * above it, mostly 10 to 30).
	 */
	private static final int START = 56;
	/** Levels 2 and 3: the next column's search starts this far above the ground found next to it. */
	private static final int NEXT = 16;
	/** {@code Sampler#exact}'s ways: every column, the range above, the range below. */
	private static final int FIRST = 0, UP = 1, DOWN = 2;
	/**
	 * Air under the ground is looked for where the ground is this far above the preliminary surface (it's mostly 10 to
	 * 30 above it): a floating island, an arch. Elsewhere the ground is taken to go all the way down.
	 */
	private static final int GAP_ABOVE = 40;
	/** The sky light of the air under an island: shaded. */
	private static final int GAP_SKY = 12;
	/** At most this many pieces of ground one over the other in a column, with air between (the lowest goes on down). */
	private static final int MAX_SEG = 8;
	/** Trees are grown at this level and below (a sheet of 16 chunks); further out they'd hardly show. */
	public static final int TREE_LEVELS = 2;
	/** A tree reaches at most this far from its trunk: the chunks this close to a sheet are grown for it too. */
	private static final int TREE_REACH = 8;
	/** Chunks whose trees a sampler keeps for the sheets next to them. */
	private static final int TREES_KEPT = 192;
	/** How far down what the surface rules put under the top block goes, for the trees' stand-in world. */
	private static final int UNDER_DEPTH = 3;

	private final NoiseGeneratorSettings settings;
	private final RandomState random;
	private final WorldGenerationContext genContext;
	private final BiomeSource biomeSource;
	private final DensityFunction prelimFunction;
	/**
	 * Whether the generator has no preliminary surface (a constant, as the floating islands' one): searches start at the
	 * top of the world and air under the ground is looked for everywhere.
	 */
	private final boolean noSurface;
	private final DensitySampler fullDensity, coarseDensity, coarsePrelim;
	/** The lowest block the land is made from and the top (exclusive). */
	public final int minY, topY;
	private final int seaLevel;
	private final boolean skyLight;
	private final BlockState defaultBlock;
	private final @Nullable FarTrees trees;
	private final ServerLevel level;
	private final NoiseBasedChunkGenerator generator;

	private FarLand(ServerLevel level, NoiseBasedChunkGenerator generator) {
		this.level = level;
		this.generator = generator;
		settings = generator.generatorSettings().value();
		random = level.getChunkSource().randomState();
		genContext = new WorldGenerationContext(generator, level);
		biomeSource = generator.getBiomeSource();
		prelimFunction = settings.noiseRouter().chunkSurfaceLevel();
		noSurface = prelimFunction.domainAxes() == DensityFunction.NO_AXES;
		fullDensity = random.getSampler(settings.noiseRouter().finalDensity());
		coarseDensity = random.getSampler(STRIP.rewrite(settings.noiseRouter().finalDensity()));
		coarsePrelim = random.getSampler(STRIP.rewrite(prelimFunction));
		minY = Math.max(level.getMinY(), settings.noiseSettings().minY());
		topY = Math.min(level.getMinY() + level.getHeight(), settings.noiseSettings().minY() + settings.noiseSettings().height());
		seaLevel = settings.seaLevel();
		skyLight = level.dimensionType().hasSkyLight();
		defaultBlock = settings.defaultBlock();
		FarTrees t = FarTrees.of(level, generator, this, settings.defaultFluid(), seaLevel);
		trees = t != null && t.any() ? t : null;
	}

	/**
	 * Null where it can't be done: a generator that isn't the noise one, a dimension with a ceiling (the Nether) or with
	 * no sky (the End).
	 */
	public static @Nullable FarLand of(ServerLevel level) {
		if (!possible(level)) return null;
		return new FarLand(level, (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator());
	}

	/** Whether it could be done for the level (cheap: nothing is set up). */
	public static boolean possible(ServerLevel level) {
		return level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator && !level.dimensionType().hasCeiling()
				&& level.dimensionType().hasSkyLight();
	}

	/**
	 * The generator's noise is made of cells: what's between their corners is interpolated. Sampled further apart than a
	 * cell, the interpolation would need every block in between, so out there it's left out: what's left is exact at the
	 * corners, and close to the real thing between them.
	 */
	private static final DfRewriteRule STRIP = new DfRewriteRule() {
		@Override
		public DensityFunction rewrite(DensityFunction function) {
			function = DfRewriteRule.INLINE_REFERENCE.rewrite(function);
			if (function instanceof InterpolatedFunction interpolated) return rewrite(interpolated.input());
			return function.rewriteChildren(this);
		}
	};

	public Sampler sampler() {
		return new Sampler();
	}

	/** Stands in for the game's shaping of the ground to buildings (asked for by the final density): the sheet's. */
	private static final class SheetBeard extends Beardifier {
		Beardifier current = Beardifier.EMPTY;

		SheetBeard() {
			super(List.of(), List.of(), null);
		}

		@Override
		public void sampleVolume(SamplerContext context, DensityBuffer out, DensityVolume volume) {
			current.sampleVolume(context, out, volume);
		}

		@Override
		public float sampleValue(SamplerContext context, int x, int y, int z) {
			return current.sampleValue(context, x, y, z);
		}
	}

	/** The buildings, if the world has any (and they're on). */
	public @Nullable FarStructures structures() {
		return trees == null ? null : trees.structures();
	}

	/**
	 * 16 x 16 columns at a level, each a list of runs from the bottom of the world up ({@code what << 16 | top}, top in
	 * voxels, exclusive); {@code what} is the block's place in {@link #palette} shifted up 8 bits, with an air voxel's sky
	 * light in bits 4-7.
	 */
	public static final class Sheet {
		public final List<@Nullable BlockState> palette = new ArrayList<>(List.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState()));
		public final long[][] columns = new long[256][];
		@SuppressWarnings("unchecked")
		public final Holder<Biome>[] biomes = new Holder[256];
		/** Per column: where the ground ends (blocks, exclusive), snow left out. */
		public final int[] ground = new int[256];
		/** Per column: the generator's preliminary surface there (for the check command). */
		public final float[] prelim = new float[256];
		private final Reference2IntOpenHashMap<BlockState> ids = new Reference2IntOpenHashMap<>();

		private Sheet() {
			ids.defaultReturnValue(-1);
		}

		int id(BlockState state) {
			int id = ids.getInt(state);
			if (id < 0) {
				id = palette.size();
				palette.add(state);
				ids.put(state, id);
			}
			return id;
		}
	}

	/** One chunk's trees, kept; exact if grown on the ground of a sheet the chunk is in (else on the edge of one next to it). */
	private record Kept(FarTrees.Grown grown, boolean exact) {
	}

	/** One thread's tools: its own noise caches and surface rules. */
	public final class Sampler implements FarTrees.Ground {
		private final SamplerContext context;
		/** The ground's shaping to the buildings around the sheet being made (the generator's final density asks for it). */
		private final SheetBeard beard = new SheetBeard();
		/** The heights that shaping can change the ground between (none when lo > hi). */
		private int shapeLo, shapeHi;
		private final DensityBufferPool pool = new DensityBufferPool(20);
		private final DensitySampler.Bound full, coarse, prelim;
		private final BiomeResolver biomes;
		private final MaterialRuleContext rules;
		private final RuleEvaluator rule;
		private final Long2ObjectOpenHashMap<Holder<Biome>> biomeMemo = new Long2ObjectOpenHashMap<>();
		private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		/** The preliminary surface on the generator's 16-block grid over the sheet, {@link #grid} points a side. */
		private float[] prelimGrid = new float[0];
		private int grid, gridX, gridZ;
		private final int[] tops = new int[256], voxels = new int[256];
		/**
		 * Per column, its pieces of ground from the top down, with air between ({@link #gapScan}): {@link #MAX_SEG} places
		 * each for their top (blocks, exclusive) and lowest block ({@link #minY} if it goes on down). Then the lowest voxel
		 * open to the sky.
		 */
		private final int[] segTops = new int[256 * MAX_SEG], segBottoms = new int[256 * MAX_SEG], segCount = new int[256], floors = new int[256];
		private long[] runs = new long[16];
		private int runCount;
		private final BlockState[] materials = new BlockState[4096];
		/** Time spent finding the ground, and on biomes and surface rules, and density samples taken (for the check command). */
		public long groundNanos, surfaceNanos, samples;
		/** Of those samples, the ones looking for air under the ground, and columns it was found in (for the check command). */
		public long gapSamples, gapColumns;
		/** Column by column ({@link #groundSearch}, {@link #groundBand}), else all in one box (the check command compares). */
		public boolean searchColumns = true;
		private final float[] prelims = new float[256];
		/** Per column while looking for the ground block by block: look higher, look lower ({@link #exact}). */
		private final int[] need = new int[256];
		/** Per column: the ground's top block and the one under it (null where there's no ground on top, or water). */
		private final BlockState[] topStates = new BlockState[256], underStates = new BlockState[256];
		private final FarTrees.@Nullable Grower grower = trees == null ? null : trees.grower();
		/** By chunk and level ({@link #treeKey}), most recent first. */
		private final Long2ObjectLinkedOpenHashMap<Kept> kept = new Long2ObjectLinkedOpenHashMap<>();
		/** The sheet the trees grow on: its first column's block and a voxel's size. */
		private int treeX0, treeZ0, treeS = 1;
		private @Nullable Sheet treeSheet;
		/** While buildings are put down ({@link #building}): the ground's height past the sheet, by column, as worked out. */
		private boolean building;
		private final Long2IntOpenHashMap pastSheet = new Long2IntOpenHashMap();
		private final LongArrayList treeKeys = new LongArrayList();
		private final Long2ObjectOpenHashMap<BlockState> treeVoxels = new Long2ObjectOpenHashMap<>();
		/** False: no trees (the check command compares). */
		public boolean growTrees = true;
		/** Time spent on trees, and chunks grown (for the check command). */
		public long treeNanos, treeChunks, treeVoxelCount;

		/** Chunks with buildings put down so far. */
		public long builtChunks() {
			return grower == null ? 0 : grower.built;
		}

		private Sampler() {
			context = SamplerContext.builder().setUserFields(ContextMap.builder().set(Beardifier.CONTEXT_KEY, beard).build()).useBufferArena(pool)
					.enableCaches().build();
			full = fullDensity.bind(context);
			coarse = coarseDensity.bind(context);
			prelim = coarsePrelim.bind(context);
			DensitySamplerSet base = random.samplersWithContext(context);
			biomes = biomeSource.createResolver(random.createClimateSampler(context));
			// The surface rules ask for the preliminary surface where they are: it's on hand for the whole sheet.
			DensitySampler.Bound known = new DensitySampler() {
				@Override
				public void sampleVolume(SamplerContext c, DensityBuffer out, DensityVolume volume) {
					DensitySampler.sampleVolumeNaive(c, out, volume, this);
				}

				@Override
				public float sampleValue(SamplerContext c, int x, int y, int z) {
					return prelimAt(x, z);
				}
			}.bind(context);
			DensitySamplerSet set = function -> function == prelimFunction ? known : base.get(function);
			rules = new MaterialRuleContext(random.surfaceSystem(), random, new DensityVolume(1, 1, 1, 0, 0, 0), set,
					p -> biome(p.getX(), p.getY(), p.getZ()), genContext, null);
			rule = settings.materialRule().value().compile(rules);
		}

		/** The sheet at {@code level} whose first column is at block x0, z0 (multiples of 16 << level). */
		public Sheet sheet(int level, int x0, int z0) {
			Sheet sheet = new Sheet();
			long t0 = System.nanoTime();
			int s = 1 << level;
			FarStructures structures = structures();
			FarStructures.Shaping shaping = structures != null && FarStructures.BEARD && level <= TREE_LEVELS
					? structures.shaping(x0 - 16, z0 - 16, x0 + (16 << level) + 15, z0 + (16 << level) + 15) : FarStructures.Shaping.NONE;
			beard.current = shaping.beardifier();
			shapeLo = shaping.lo();
			shapeHi = shaping.hi();
			biomeMemo.clear();
			readPrelim(x0, z0, 16 << level);
			for (int c = 0; c < 256; c++) prelims[c] = prelimAt(x0 + (c & 15) * s, z0 + (c >> 4) * s);
			// The shaping to buildings is too sharp for samples 8 blocks apart: where there's some, block by block.
			boolean exactly = level <= 1 || shapeLo <= shapeHi;
			if (searchColumns) {
				if (exactly) groundBand(x0, z0, s);
				else groundSearch(x0, z0, s);
			} else {
				float lowest = Float.MAX_VALUE, highest = -Float.MAX_VALUE;
				for (float p : prelimGrid) {
					lowest = Math.min(lowest, p);
					highest = Math.max(highest, p);
				}
				if (shapeLo <= shapeHi) {
					lowest = Math.min(lowest, shapeLo);
					highest = Math.max(highest, shapeHi);
				}
				int lo = Mth.clamp(Mth.floor(lowest) - BELOW & ~7, minY, topY - 8), hi = Mth.clamp(Mth.ceil(highest) + ABOVE + 7 & ~7, lo + 8, topY);
				for (int round = 0; ; round++) {
					int found = exactly ? groundExact(x0, z0, s, lo, hi) : groundCoarse(x0, z0, s, lo, hi);
					boolean up = (found & 1) != 0 && hi < topY, down = (found & 2) != 0 && lo > minY;
					if (!up && !down || round == 4) break;
					if (up) hi = Math.min(topY, hi + MORE);
					if (down) lo = Math.max(minY, lo - MORE);
				}
			}
			for (int c = 0; c < 256; c++) gapScan(c, x0 + (c & 15) * s, z0 + (c >> 4) * s);
			long t1 = System.nanoTime();
			groundNanos += t1 - t0;
			for (int c = 0; c < 256; c++) {
				sheet.prelim[c] = prelims[c];
				sheet.ground[c] = tops[c];
				voxels[c] = Math.ceilDiv(tops[c] - minY, s);
				int last = c * MAX_SEG + segCount[c] - 1;
				floors[c] = segBottoms[last] <= minY ? Math.ceilDiv(segTops[last] - minY, s) : 0;
			}
			int height = (topY - minY + s - 1) >> level;
			int air = (skyLight ? 15 : 0) << 4, gapAir = (skyLight ? GAP_SKY : 0) << 4;
			int waterTop = Math.ceilDiv(seaLevel - minY, s);
			BlockState water = settings.defaultFluid();
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					int c = z * 16 + x;
					int bx = x0 + x * s, bz = z0 + z * s;
					int top = tops[c], n = voxels[c];
					boolean submerged = waterTop > n && !water.isAir();
					boolean floating = segCount[c] > 1 || segBottoms[c * MAX_SEG] > minY;
					runCount = 0;
					topStates[c] = null;
					if (n > 0 && floating) {
						// Ground over air (floating islands, an arch): the pieces from the bottom up.
						Holder<Biome> biome = biome(bx, top - 1, bz);
						sheet.biomes[c] = biome;
						rules.updateXZ(bx, bz, gradient(x, z, 1, s), gradient(x, z, 16, s));
						int k = c * MAX_SEG, end = 0, under = Integer.MIN_VALUE;
						for (int i = segCount[c] - 1; i >= 0; i--) {
							int t = segTops[k + i], b = segBottoms[k + i];
							int vt = Math.ceilDiv(t - minY, s), vb = b <= minY ? 0 : Math.floorDiv(b - minY, s);
							// At this level it's in the piece under it.
							if (vt <= end) continue;
							if (vb > end) {
								// The air under it; over ground below the sea's level, the sea.
								int wet = under != Integer.MIN_VALUE && under < seaLevel && !water.isAir() ? Math.min(waterTop, vb) : end;
								if (wet > end) add(sheet.id(water) << 8, wet);
								if (vb > Math.max(end, wet)) add(gapAir, vb);
								end = vb;
							}
							if (b <= minY && t < seaLevel && !water.isAir()) {
								add(HIDDEN << 8, vt);
							} else {
								int low = b <= minY ? low(c, x, z, vt) : end;
								materials(bx, bz, s, t, vt, low, Math.max(b, minY), t < seaLevel ? seaLevel : Integer.MIN_VALUE);
								if (i == 0) topStates(c, vt, low);
								boolean snowy = i == 0 && snowy(biome, bx, bz, t, vt, low);
								int snowEnd = snowy ? Math.min(height, Math.ceilDiv(t + 1 - minY, s)) : vt;
								addGround(sheet, vt, low, snowy ? snowEnd - 1 : -1);
								if (snowEnd > vt) addMerging(sheet.id(Blocks.SNOW.defaultBlockState()) << 8, snowEnd);
								vt = snowEnd;
							}
							end = vt;
							under = t;
						}
						if (under < seaLevel && waterTop > end && !water.isAir()) {
							add(sheet.id(water) << 8, waterTop);
							end = waterTop;
						}
						if (end < height) add(air, height);
					} else if (submerged) {
						Holder<Biome> biome = biome(bx, seaLevel - 1, bz);
						sheet.biomes[c] = biome;
						if (n > 0) add(HIDDEN << 8, n);
						boolean ice = water.is(Blocks.WATER) && biome.value().coldEnoughToSnow(pos.set(bx, seaLevel - 1, bz), seaLevel);
						if (ice) {
							if (waterTop - 1 > n) add(sheet.id(water) << 8, waterTop - 1);
							add(sheet.id(Blocks.ICE.defaultBlockState()) << 8, waterTop);
						} else {
							add(sheet.id(water) << 8, waterTop);
						}
						if (waterTop < height) add(air, height);
					} else if (n <= 0) {
						sheet.biomes[c] = biome(bx, Math.max(top, minY), bz);
						add(air, height);
					} else {
						Holder<Biome> biome = biome(bx, top - 1, bz);
						sheet.biomes[c] = biome;
						int low = low(c, x, z, n);
						rules.updateXZ(bx, bz, gradient(x, z, 1, s), gradient(x, z, 16, s));
						materials(bx, bz, s, top, n, low, minY, top < seaLevel ? seaLevel : Integer.MIN_VALUE);
						topStates(c, n, low);
						// Snow where the game would put it on top: it counts toward the height, as a chunk's does.
						boolean snowy = snowy(biome, bx, bz, top, n, low);
						int end = snowy ? Math.min(height, Math.ceilDiv(top + 1 - minY, s)) : n;
						addGround(sheet, n, low, snowy ? end - 1 : -1);
						if (end > n) addMerging(sheet.id(Blocks.SNOW.defaultBlockState()) << 8, end);
						if (end < height) add(air, height);
					}
					sheet.columns[c] = Arrays.copyOf(runs, runCount);
				}
			}
			long t2 = System.nanoTime();
			surfaceNanos += t2 - t1;
			if (grower != null && growTrees && level <= TREE_LEVELS) {
				plantTrees(sheet, level, x0, z0, s);
				treeNanos += System.nanoTime() - t2;
			}
			pool.garbageCollect();
			return sheet;
		}

		private void topStates(int c, int n, int low) {
			topStates[c] = materials[n - 1 - low];
			underStates[c] = n - 2 >= low ? materials[n - 2 - low] : defaultBlock;
		}

		// ---- Trees ----

		/**
		 * The sheet's trees: those of the chunks it covers and of those next to it that reach in, grown on its ground
		 * (out past its edge, its edge's), into its columns over the ground. At level 0 block by block (a changed top
		 * block too: podzol, dirt under a trunk); above that as the far terrain makes a level from the one below: a voxel
		 * is solid where at least half of the 8 below it are. Snow on top where it'd fall, none under the trees.
		 */
		private void plantTrees(Sheet sheet, int level, int x0, int z0, int s) {
			treeX0 = x0;
			treeZ0 = z0;
			treeS = s;
			treeSheet = sheet;
			pastSheet.clear();
			pastSheet.defaultReturnValue(Integer.MIN_VALUE);
			int span = 16 << level;
			int height = (topY - minY + s - 1) >> level;
			Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
			for (int cz = (z0 - TREE_REACH) >> 4; cz <= (z0 + span + TREE_REACH - 1) >> 4; cz++) {
				for (int cx = (x0 - TREE_REACH) >> 4; cx <= (x0 + span + TREE_REACH - 1) >> 4; cx++) {
					boolean inside = cx << 4 >= x0 && (cx << 4) + 16 <= x0 + span && cz << 4 >= z0 && (cz << 4) + 16 <= z0 + span;
					long key = treeKey(level, cx, cz);
					Kept k = kept.getAndMoveToFirst(key);
					if (k == null || inside && !k.exact()) {
						k = new Kept(grower.grow(cx, cz, this, level == 0, inside), inside);
						treeChunks++;
						kept.putAndMoveToFirst(key, k);
						if (kept.size() > TREES_KEPT) kept.removeLast();
					}
					long[] positions = k.grown().positions();
					BlockState[] states = k.grown().states();
					for (int i = 0; i < positions.length; i++) {
						int x = BlockPos.getX(positions[i]) - x0, y = BlockPos.getY(positions[i]) - minY, z = BlockPos.getZ(positions[i]) - z0;
						if (x < 0 || z < 0 || x >= span || z >= span || y < 0 || !FarTrees.shows(states[i])) continue;
						blocks.put(pack(x, y, z), states[i]);
					}
				}
			}
			if (blocks.isEmpty()) return;
			treeVoxels.clear();
			if (level == 0) {
				for (Long2ObjectOpenHashMap.Entry<BlockState> e : blocks.long2ObjectEntrySet()) {
					long p = e.getLongKey();
					int c = unpackZ(p) * 16 + unpackX(p), v = unpackY(p), n = voxels[c];
					// Over the ground, or its top block changed (where it's ground on top, not under water).
					if (v >= n || v == n - 1 && topStates[c] != null && what(sheet.columns[c], v) >>> 8 != HIDDEN) {
						treeVoxels.put(voxel(c, v), e.getValue());
					}
				}
			} else {
				addSnowTops(blocks, x0, z0);
				Long2ObjectOpenHashMap<BlockState> cur = blocks;
				for (int k = 1; k <= level; k++) cur = halve(cur);
				for (Long2ObjectOpenHashMap.Entry<BlockState> e : cur.long2ObjectEntrySet()) {
					long p = e.getLongKey();
					int c = unpackZ(p) * 16 + unpackX(p), v = unpackY(p);
					if (v >= voxels[c] && v < height) treeVoxels.put(voxel(c, v), e.getValue());
				}
			}
			treeVoxelCount += treeVoxels.size();
			if (treeVoxels.isEmpty()) return;
			treeKeys.clear();
			treeKeys.addAll(treeVoxels.keySet());
			long[] keys = treeKeys.elements();
			int count = treeKeys.size();
			LongArrays.quickSort(keys, 0, count);
			int snowWhat = sheet.id(Blocks.SNOW.defaultBlockState()) << 8, air = (skyLight ? 15 : 0) << 4;
			int[] vs = new int[height + 2], whats = new int[height + 2];
			for (int i = 0; i < count; ) {
				int c = (int) (keys[i] >>> 32), end = i, m = 0;
				while (end < count && (int) (keys[end] >>> 32) == c) end++;
				int n = voxels[c], topTree = (int) keys[end - 1];
				long[] old = sheet.columns[c];
				// Snow on the ground goes up onto the trees.
				boolean above = topTree >= n, groundSnow = above && n < height && what(old, n) == snowWhat, cleared = false;
				for (; i < end; i++) {
					int v = (int) keys[i];
					if (groundSnow && !cleared && v >= n) {
						if (v > n) {
							vs[m] = n;
							whats[m++] = air;
						}
						cleared = true;
					}
					vs[m] = v;
					whats[m++] = sheet.id(treeVoxels.get(keys[i])) << 8;
				}
				if (level == 0 && above && topTree + 1 < height && (groundSnow || snowsAt(c, topTree + 1))) {
					vs[m] = topTree + 1;
					whats[m++] = snowWhat;
				}
				sheet.columns[c] = overlay(old, vs, whats, m);
			}
		}

		/** Level 1 and up: snow on top of the trees' block columns where it'd fall (level 0 does it voxel by voxel). */
		private void addSnowTops(Long2ObjectOpenHashMap<BlockState> blocks, int x0, int z0) {
			Long2IntOpenHashMap highest = new Long2IntOpenHashMap();
			highest.defaultReturnValue(-1);
			for (long p : blocks.keySet()) {
				long column = pack(unpackX(p), 0, unpackZ(p));
				highest.put(column, Math.max(highest.get(column), unpackY(p)));
			}
			BlockState snow = Blocks.SNOW.defaultBlockState();
			for (Long2IntMap.Entry e : highest.long2IntEntrySet()) {
				long column = e.getLongKey();
				int x = unpackX(column), z = unpackZ(column), y = e.getIntValue() + 1;
				int c = Math.min(z / treeS, 15) * 16 + Math.min(x / treeS, 15);
				Holder<Biome> biome = sheet0Biome(c);
				if (biome != null && minY + y < topY && biome.value().coldEnoughToSnow(pos.set(x0 + x, minY + y, z0 + z), seaLevel)) {
					blocks.put(pack(x, y, z), snow);
				}
			}
		}

		/** Whether snow would fall on voxel {@code v} of column {@code c} (level 0). */
		private boolean snowsAt(int c, int v) {
			Holder<Biome> biome = sheet0Biome(c);
			return biome != null && biome.value().coldEnoughToSnow(pos.set(treeX0 + (c & 15) * treeS, minY + v * treeS, treeZ0 + (c >> 4) * treeS), seaLevel);
		}

		private @Nullable Holder<Biome> sheet0Biome(int c) {
			return treeSheet == null ? null : treeSheet.biomes[c];
		}

		/**
		 * One level up: a voxel is solid where at least 4 of the 8 below it are, as the most common block of the upper 4
		 * (else the lower), as the far terrain makes its levels.
		 */
		private Long2ObjectOpenHashMap<BlockState> halve(Long2ObjectOpenHashMap<BlockState> below) {
			Long2ObjectOpenHashMap<BlockState[]> parents = new Long2ObjectOpenHashMap<>();
			for (Long2ObjectOpenHashMap.Entry<BlockState> e : below.long2ObjectEntrySet()) {
				long p = e.getLongKey();
				int x = unpackX(p), y = unpackY(p), z = unpackZ(p);
				BlockState[] children = parents.computeIfAbsent(pack(x >> 1, y >> 1, z >> 1), k -> new BlockState[8]);
				children[(y & 1) * 4 + (z & 1) * 2 + (x & 1)] = e.getValue();
			}
			Long2ObjectOpenHashMap<BlockState> out = new Long2ObjectOpenHashMap<>();
			for (Long2ObjectOpenHashMap.Entry<BlockState[]> e : parents.long2ObjectEntrySet()) {
				BlockState[] children = e.getValue();
				int solid = 0;
				for (BlockState b : children) if (b != null) solid++;
				if (solid < 4) continue;
				BlockState state = mostCommon(children, 4);
				out.put(e.getLongKey(), state != null ? state : mostCommon(children, 0));
			}
			return out;
		}

		private static @Nullable BlockState mostCommon(BlockState[] children, int from) {
			BlockState best = null;
			int bestCount = 0;
			for (int i = from; i < from + 4; i++) {
				if (children[i] == null) continue;
				int n = 0;
				for (int j = from; j < from + 4; j++) if (children[j] == children[i]) n++;
				if (n > bestCount) {
					best = children[i];
					bestCount = n;
				}
			}
			return best;
		}

		/** The column's value at voxel {@code v}, -1 past its top. */
		private static int what(long[] runs, int v) {
			for (long r : runs) if (v < (int) (r & 0xFFFF)) return (int) (r >>> 16);
			return -1;
		}

		/** The column's runs with voxels {@code vs} (rising) set to {@code whats}. */
		private long[] overlay(long[] old, int[] vs, int[] whats, int n) {
			runCount = 0;
			int bottom = 0, k = 0;
			for (long r : old) {
				int top = (int) (r & 0xFFFF), what = (int) (r >>> 16), y = bottom;
				for (; k < n && vs[k] < top; k++) {
					if (vs[k] < y) continue;
					if (vs[k] > y) addMerging(what, vs[k]);
					addMerging(whats[k], vs[k] + 1);
					y = vs[k] + 1;
				}
				if (y < top) addMerging(what, top);
				bottom = top;
			}
			return Arrays.copyOf(runs, runCount);
		}

		private static long treeKey(int level, int cx, int cz) {
			return ((long) cx & 0xFFFFFFFL) << 32 | ((long) cz & 0xFFFFFFFL) << 4 | level;
		}

		/** A block or voxel in the sheet: x and z from its corner (10 bits each), y from the bottom (12). */
		private static long pack(int x, int y, int z) {
			return (long) y << 20 | (long) z << 10 | x;
		}

		private static int unpackX(long p) {
			return (int) (p & 1023);
		}

		private static int unpackZ(long p) {
			return (int) (p >> 10 & 1023);
		}

		private static int unpackY(long p) {
			return (int) (p >> 20);
		}

		private static long voxel(int c, int v) {
			return (long) c << 32 | v;
		}

		// The trees' ground (FarTrees.Ground): the sheet's, out past its edge its edge's.

		@Override
		public int top(int x, int z) {
			int s = treeS;
			if (building && (x < treeX0 || z < treeZ0 || x >= treeX0 + 16 * s || z >= treeZ0 + 16 * s)) {
				long column = BlockPos.asLong(x, 0, z);
				int top = pastSheet.get(column);
				if (top == Integer.MIN_VALUE) {
					top = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
					pastSheet.put(column, top);
				}
				return top;
			}
			if (s == 1) return tops[Mth.clamp(z - treeZ0, 0, 15) * 16 + Mth.clamp(x - treeX0, 0, 15)];
			float fx = Mth.clamp((x - treeX0) / (float) s, 0.0F, 15.0F), fz = Mth.clamp((z - treeZ0) / (float) s, 0.0F, 15.0F);
			int i = Math.min((int) fx, 14), j = Math.min((int) fz, 14);
			int a = tops[j * 16 + i], b = tops[j * 16 + i + 1], c = tops[j * 16 + i + 16], d = tops[j * 16 + i + 17];
			// Between columns the ground's worked out from the four around, except over a cliff (or an island's edge).
			if (Math.max(Math.max(a, b), Math.max(c, d)) - Math.min(Math.min(a, b), Math.min(c, d)) > 4 * s) {
				return tops[Math.round(fz) * 16 + Math.round(fx)];
			}
			float tx = fx - i, tz = fz - j;
			return Math.round(Mth.lerp(tz, Mth.lerp(tx, a, b), Mth.lerp(tx, c, d)));
		}

		private int nearest(int x, int z) {
			int i = Mth.clamp(Math.round((x - treeX0) / (float) treeS), 0, 15), j = Mth.clamp(Math.round((z - treeZ0) / (float) treeS), 0, 15);
			return j * 16 + i;
		}

		@Override
		public BlockState surface(int x, int z) {
			int c = nearest(x, z);
			BlockState state = topStates[c];
			if (state != null) return state;
			// Shallow water (mangroves grow in it, up to 5 deep): its floor worked out now.
			int top = tops[c], n = voxels[c];
			if (top >= seaLevel || top <= minY || seaLevel - top > 5 || n <= 0) return defaultBlock;
			int cx = c & 15, cz = c >> 4, bx = treeX0 + cx * treeS, bz = treeZ0 + cz * treeS;
			rules.updateXZ(bx, bz, gradient(cx, cz, 1, treeS), gradient(cx, cz, 16, treeS));
			materials(bx, bz, treeS, top, n, n - 1, minY, seaLevel);
			topStates[c] = materials[0];
			underStates[c] = defaultBlock;
			return materials[0];
		}

		@Override
		public BlockState under(int x, int z, int depth) {
			// What the surface rules put under the top (dirt, mud) goes a few blocks down, then the stone (mangrove roots
			// grow down through mud, as far as it goes).
			BlockState state = underStates[nearest(x, z)];
			return state != null && depth <= UNDER_DEPTH ? state : defaultBlock;
		}

		@Override
		public void building(boolean on) {
			building = on;
		}

		@Override
		public Holder<Biome> noiseBiome(int quartX, int quartY, int quartZ) {
			int qy = Mth.clamp(quartY, minY >> 2, (topY - 1) >> 2);
			long key = BlockPos.asLong(quartX, qy, quartZ);
			Holder<Biome> biome = biomeMemo.get(key);
			if (biome == null) {
				biome = biomes.getNoiseBiome(quartX, qy, quartZ);
				biomeMemo.put(key, biome);
			}
			return biome;
		}

		/**
		 * Ground going on down to voxel {@code n}: down to the lowest open air next to it, below that nothing sees it (a
		 * sheet's edge: 4 voxels).
		 */
		private int low(int c, int x, int z, int n) {
			int low = n - 1;
			low = Math.min(low, x > 0 ? floors[c - 1] : n - 4);
			low = Math.min(low, x < 15 ? floors[c + 1] : n - 4);
			low = Math.min(low, z > 0 ? floors[c - 16] : n - 4);
			low = Math.min(low, z < 15 ? floors[c + 16] : n - 4);
			return Math.max(low, Math.max(0, n - materials.length));
		}

		/** The ground's slope across a column, along x ({@code step} 1) or z (16), in blocks a voxel. */
		private int gradient(int x, int z, int step, int s) {
			int i = step == 1 ? x : z, row = step == 1 ? z * 16 : x;
			return (tops[row + Math.min(i + 1, 15) * step] - tops[row + Math.max(i - 1, 0) * step]) / s;
		}

		/** Whether the game would put snow on ground whose top is {@code top}, top voxel {@code n - 1} in {@link #materials}. */
		private boolean snowy(Holder<Biome> biome, int bx, int bz, int top, int n, int low) {
			return top >= seaLevel && top < topY && biome.value().coldEnoughToSnow(pos.set(bx, top, bz), seaLevel)
					&& snowCanLie(materials[n - 1 - low]);
		}

		/**
		 * What the surface rules put in voxels {@code low} to {@code n - 1} of ground whose top is {@code top} (blocks),
		 * into {@link #materials}; {@code base} is the lowest block of that ground (for rules on the stone under them).
		 */
		private void materials(int bx, int bz, int s, int top, int n, int low, int base, int waterHeight) {
			for (int v = n - 1; v >= low; v--) {
				int y = Math.min(minY + (v + 1) * s - 1, top - 1);
				int depth = top - y, below = y - base + 1;
				BlockState state = null;
				if (depth <= RULE_DEPTH || base > minY && below <= 8) {
					rules.updateY(depth, below, waterHeight, y);
					state = rule.tryApply(bx, y, bz);
				}
				materials[v - low] = state != null ? state : defaultBlock;
			}
		}

		/** The runs of that ground: hidden up to {@code low}, then {@link #materials}, snow in voxel {@code snow} (-1: none). */
		private void addGround(Sheet sheet, int n, int low, int snow) {
			if (low > (runCount == 0 ? 0 : (int) runs[runCount - 1] & 0xFFFF)) add(HIDDEN << 8, low);
			int snowWhat = snow >= 0 ? sheet.id(Blocks.SNOW.defaultBlockState()) << 8 : 0;
			for (int v = low; v < n; v++) addMerging(v == snow ? snowWhat : sheet.id(materials[v - low]) << 8, v + 1);
		}

		private boolean snowCanLie(BlockState state) {
			return !state.is(Blocks.ICE) && !state.is(Blocks.PACKED_ICE) && !state.is(Blocks.BLUE_ICE) && !state.is(Blocks.SNOW_BLOCK)
					&& !state.is(Blocks.POWDER_SNOW) && state.getFluidState().isEmpty();
		}

		private void add(int what, int top) {
			if (runCount == runs.length) runs = Arrays.copyOf(runs, runCount * 2);
			runs[runCount++] = (long) what << 16 | top;
		}

		private void addMerging(int what, int top) {
			if (runCount > 0 && (int) (runs[runCount - 1] >>> 16) == what) {
				runs[runCount - 1] = (long) what << 16 | top;
			} else {
				add(what, top);
			}
		}

		private void readPrelim(int x0, int z0, int span) {
			grid = (span >> 4) + 1;
			gridX = x0;
			gridZ = z0;
			if (prelimGrid.length != grid * grid) prelimGrid = new float[grid * grid];
			DensityVolume volume = new DensityVolume(grid, 1, grid, x0, 0, z0, 16, 1, 16);
			try (ScopedDensityBuffer buffer = prelim.sampleVolume(volume)) {
				for (int i = 0; i < prelimGrid.length; i++) prelimGrid[i] = buffer.get(i);
			}
		}

		/** The preliminary surface the way the generator works it out: between the grid's points, interpolated. */
		private float prelimAt(int x, int z) {
			int rx = x - gridX, rz = z - gridZ, span = (grid - 1) * 16;
			if (rx < 0 || rz < 0 || rx > span || rz > span) return random.getSampler(prelimFunction).sampleValue(context, x, 0, z);
			int gx = Math.min(rx >> 4, grid - 2), gz = Math.min(rz >> 4, grid - 2);
			float fx = (rx - gx * 16) / 16.0F, fz = (rz - gz * 16) / 16.0F;
			float a = Mth.lerp(fx, prelimGrid[gz * grid + gx], prelimGrid[gz * grid + gx + 1]);
			float b = Mth.lerp(fx, prelimGrid[(gz + 1) * grid + gx], prelimGrid[(gz + 1) * grid + gx + 1]);
			return Mth.lerp(fz, a, b);
		}

		/**
		 * Levels 0 and 1 (and 2 by buildings): the density block by block between {@code lo} and {@code hi}, as the generator works it out.
		 * Bit 1 of the answer: some column is solid at the top (look higher); bit 2: some has no ground (look lower).
		 */
		private int groundExact(int x0, int z0, int s, int lo, int hi) {
			int h = hi - lo, found = 0;
			DensityVolume volume = new DensityVolume(16, h, 16, x0, lo, z0, s, 1, s);
			samples += 256L * h;
			try (ScopedDensityBuffer buffer = full.sampleVolume(volume)) {
				for (int c = 0; c < 256; c++) {
					int base = c * h, y = h - 1;
					while (y >= 0 && buffer.get(base + y) <= 0.0F) y--;
					if (y == h - 1) found |= 1;
					if (y < 0) {
						found |= 2;
						tops[c] = lo == minY ? minY : lo;
					} else {
						tops[c] = lo + y + 1;
					}
				}
			}
			return found;
		}

		/**
		 * Levels 2 and up, column by column: the density every 8 blocks (on the generator's noise cell corners), from a
		 * little above where the ground's expected, down until it's solid (or up until it isn't); the ground's top is
		 * worked out between the last two. Far fewer samples than a box over the whole sheet: a column's ground is
		 * within a few samples of where it's expected, a sheet's ground can span a mountain.
		 */
		private void groundSearch(int x0, int z0, int s) {
			for (int c = 0; c < 256; c++) {
				// The land goes on: just above the ground next to it (the higher one of the two done, so an island found
				// goes on too) is a better guess than the preliminary surface. Not at level 4 (16 blocks apart), where that
				// misses ledges it's under.
				int guess;
				if (noSurface) guess = topY;
				else if (c == 0 || s >= 16) guess = Mth.floor(prelims[c]) + START;
				else guess = Math.max((c & 15) > 0 ? tops[c - 1] : Integer.MIN_VALUE, c >= 16 ? tops[c - 16] : Integer.MIN_VALUE) + NEXT;
				tops[c] = searchColumn(x0 + (c & 15) * s, z0 + (c >> 4) * s, guess);
			}
		}

		/** The ground's top in one column, found 8 blocks at a time from the guess ({@link #groundSearch}). */
		private int searchColumn(int bx, int bz, int guess) {
			int y = Mth.clamp(guess & ~7, minY, topY);
			float d = coarse.sampleValue(bx, y, bz);
			samples++;
			if (d > 0.0F) {
				while (true) {
					int up = y + 8;
					if (up > topY) return Math.min(topY, y + 1);
					float e = coarse.sampleValue(bx, up, bz);
					samples++;
					if (e <= 0.0F) {
						// Air, but over a ledge there may be more ground: one more sample above to be sure.
						if (up + 8 <= topY) {
							float f = coarse.sampleValue(bx, up + 8, bz);
							samples++;
							if (f > 0.0F) {
								y = up + 8;
								d = f;
								continue;
							}
						}
						return Math.min(topY, crossing(y, d, e));
					}
					y = up;
					d = e;
				}
			}
			while (true) {
				int down = y - 8;
				if (down < minY) return minY;
				float e = coarse.sampleValue(bx, down, bz);
				samples++;
				if (e > 0.0F) return Math.min(topY, crossing(down, e, d));
				y = down;
				d = e;
			}
		}

		/**
		 * Levels 0 and 1 (and 2 by buildings): block by block, as the generator does, but only between heights worked out first from the
		 * corners of its noise cells (every 4 blocks across, 8 up), where its noise is exact: between corners it's
		 * interpolated, so no ground in a cell is above the highest of its corners' or below the lowest (caves aside;
		 * where it is, the range grows until it's found). The shaping to buildings isn't interpolated: the range takes in
		 * the heights it can change.
		 */
		private void groundBand(int x0, int z0, int s) {
			int n = (16 * s >> 2) + 1, lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE, previous = 0, rowFirst = 0;
			for (int j = 0; j < n; j++) {
				for (int i = 0; i < n; i++) {
					int bx = x0 + 4 * i, bz = z0 + 4 * j;
					int guess = noSurface ? topY : i == 0 && j == 0 ? Mth.floor(prelims[0]) + START : previous + NEXT;
					int top = searchColumn(bx, bz, guess);
					// The next guess: the one before in the row, or the row's first one before.
					if (i == 0) rowFirst = top;
					previous = i == n - 1 ? rowFirst : top;
					lowest = Math.min(lowest, top);
					highest = Math.max(highest, top);
				}
			}
			if (shapeLo <= shapeHi) {
				lowest = Math.min(lowest, shapeLo);
				highest = Math.max(highest, shapeHi);
			}
			int lo = Mth.clamp(lowest - 8 & ~7, minY, topY - 8), hi = Mth.clamp(highest + 8 + 7 & ~7, lo + 8, topY);
			int want = exact(x0, z0, s, lo, hi, FIRST);
			for (int round = 0; round < 4 && (want & UP) != 0 && hi < topY; round++) {
				int next = Math.min(topY, hi + MORE);
				want = want & DOWN | exact(x0, z0, s, hi, next, UP);
				hi = next;
			}
			for (int round = 0; round < 4 && (want & DOWN) != 0 && lo > minY; round++) {
				int next = Math.max(minY, lo - MORE);
				want = exact(x0, z0, s, next, lo, DOWN);
				lo = next;
			}
		}

		/**
		 * The ground's top block by block between {@code lo} and {@code hi} ({@link #groundBand}), in every column the
		 * first time, else in those still needing it this way ({@link #need}). What's left: UP where the range's top
		 * block is solid (not when looking lower), DOWN where nothing is (not when looking higher: the block below the
		 * range was solid).
		 */
		private int exact(int x0, int z0, int s, int lo, int hi, int mode) {
			int h = hi - lo, left = 0;
			DensityVolume volume = new DensityVolume(16, h, 16, x0, lo, z0, s, 1, s);
			samples += 256L * h;
			try (ScopedDensityBuffer buffer = full.sampleVolume(volume)) {
				for (int c = 0; c < 256; c++) {
					if (mode != FIRST && (need[c] & mode) == 0) continue;
					int base = c * h, y = h - 1;
					while (y >= 0 && buffer.get(base + y) <= 0.0F) y--;
					int flags = 0;
					if (y == h - 1 && mode != DOWN) {
						tops[c] = hi;
						flags = UP;
					} else if (y >= 0) {
						tops[c] = lo + y + 1;
					} else {
						tops[c] = lo;
						if (mode != UP && lo > minY) flags = DOWN;
					}
					need[c] = flags;
					left |= flags;
				}
			}
			return left;
		}

		/**
		 * Where the ground ends between a solid sample ({@code a} at {@code y}) and the air sample 8 blocks above (the same
		 * sum gives where it starts above air: its lowest block).
		 */
		private static int crossing(int y, float a, float b) {
			return Mth.clamp(Mth.ceil(y + 8.0F * a / (a - b)), y + 1, y + 8);
		}

		/**
		 * The pieces of ground in a column, from the one found ({@link #tops}) down: air under it (a floating island, an
		 * arch) and the ground under that (none: the void), and so on. The density every 8 blocks down from a piece's
		 * top, to a little below the preliminary surface, and only where the piece is well above that surface (or there's
		 * none): a piece that isn't goes on down. Air two samples running counts, so not a small cave a sample hits.
		 */
		private void gapScan(int c, int bx, int bz) {
			int k = c * MAX_SEG, m = 0, top = tops[c];
			int limit = noSurface ? minY : Math.max(minY, Mth.floor(prelims[c]) - 8);
			// The last solid sample, taken where the next piece's search starts.
			int knownY = Integer.MIN_VALUE;
			float known = 0.0F;
			while (true) {
				segTops[k + m] = top;
				segBottoms[k + m] = minY;
				m++;
				if (m == MAX_SEG || !noSurface && top <= prelims[c] + GAP_ABOVE) break;
				int y = top - 1 & ~7;
				if (y - 8 < limit) break;
				float above = 1.0F, d = y == knownY ? known : sample(bx, y, bz), e = 0.0F;
				boolean first = true, found = false;
				while (y - 8 >= limit) {
					e = sample(bx, y - 8, bz);
					if (d <= 0.0F && e <= 0.0F) {
						found = true;
						break;
					}
					above = d;
					d = e;
					y -= 8;
					first = false;
				}
				if (!found) break;
				segBottoms[k + m - 1] = first ? Math.min(top - 1, y + 1) : crossing(y, d, above);
				if (m == 1) gapColumns++;
				int lower = Integer.MIN_VALUE;
				for (int ay = y - 8; ay - 8 >= minY; ay -= 8) {
					float f = sample(bx, ay - 8, bz);
					if (f > 0.0F) {
						lower = crossing(ay - 8, f, e);
						knownY = ay - 8;
						known = f;
						break;
					}
					e = f;
				}
				// Nothing under it: the void.
				if (lower == Integer.MIN_VALUE) break;
				top = lower;
			}
			segCount[c] = m;
		}

		private float sample(int bx, int y, int bz) {
			samples++;
			gapSamples++;
			return coarse.sampleValue(bx, y, bz);
		}

		/** Levels 2 and up: the density every 8 blocks, the ground's top worked out between the two around it. */
		private int groundCoarse(int x0, int z0, int s, int lo, int hi) {
			int n = (hi - lo) / 8 + 1, found = 0;
			DensityVolume volume = new DensityVolume(16, n, 16, x0, lo, z0, s, 8, s);
			samples += 256L * n;
			try (ScopedDensityBuffer buffer = coarse.sampleVolume(volume)) {
				for (int c = 0; c < 256; c++) {
					int base = c * n, k = n - 1;
					while (k >= 0 && buffer.get(base + k) <= 0.0F) k--;
					if (k == n - 1) found |= 1;
					if (k < 0) {
						found |= 2;
						tops[c] = lo == minY ? minY : lo;
					} else if (k == n - 1) {
						tops[c] = Math.min(topY, lo + 8 * k + 1);
					} else {
						float a = buffer.get(base + k), b = buffer.get(base + k + 1);
						int y = lo + 8 * k;
						tops[c] = Mth.clamp(Mth.ceil(y + 8.0F * a / (a - b)), y + 1, y + 8);
					}
				}
			}
			return found;
		}

		/** The biome the generator picks there (without the blur between biomes a chunk gets). */
		private Holder<Biome> biome(int x, int y, int z) {
			long key = BlockPos.asLong(x >> 2, y >> 2, z >> 2);
			Holder<Biome> biome = biomeMemo.get(key);
			if (biome == null) {
				biome = biomes.getNoiseBiome(x >> 2, y >> 2, z >> 2);
				biomeMemo.put(key, biome);
			}
			return biome;
		}
	}
}
