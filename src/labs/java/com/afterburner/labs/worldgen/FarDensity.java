package com.afterburner.labs.worldgen;

import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * One thread's view of a generator's noise ({@link FarLand.Sampler}'s): its density functions as the game's NoiseChunk
 * works them out for a chunk, but anywhere. The generator's noise is made of cells: what's between their corners is
 * interpolated. {@link #exact} interpolates it as the generator does, so the ground is where the real chunks' ground will
 * be, block for block. Sampled further apart than a cell, that would need every corner in between, so out there
 * {@link #coarse} leaves it out: exact at the corners, and close to the real thing between them. The flat caches are
 * filled as the generator fills them (on the 4-block grid, at y 0), and the preliminary surface is found as it finds it.
 */
final class FarDensity {
	/** What the generator's preliminary surface is over (NoiseChunk#computePreliminarySurfaceLevel). */
	private static final double SURFACE = 0.390625;
	/** Corners and columns kept a cache: a power of two. */
	private static final int KEPT = 4096;
	/** The game's markers, told apart by their kind (named in the data, not here). */
	private static final Object INTERPOLATED = kind(DensityFunctions.interpolated(DensityFunctions.zero()));
	private static final Object FLAT_CACHE = kind(DensityFunctions.flatCache(DensityFunctions.zero()));
	private static final Object CACHE_2D = kind(DensityFunctions.cache2d(DensityFunctions.zero()));

	private final DensityFunction exact, coarse, initial;
	final Climate.Sampler climate;
	private final int cellWidth, cellHeight, minY, height;
	private final Point point = new Point();
	/** The ground's shaping to buildings, which the generator adds to its final density. */
	private @Nullable Beardifier beard;

	FarDensity(RandomState random, NoiseGeneratorSettings settings) {
		NoiseSettings noise = settings.noiseSettings();
		cellWidth = noise.getCellWidth();
		cellHeight = noise.getCellHeight();
		minY = noise.minY();
		height = noise.height();
		NoiseRouter router = random.router();
		// The same function met twice is made once, as RandomState wires them: its caches are shared.
		Map<DensityFunction, DensityFunction> made = new HashMap<>();
		Visitor plain = new Visitor(made, false);
		exact = router.finalDensity().mapAll(new Visitor(made, true));
		coarse = router.finalDensity().mapAll(plain);
		initial = router.initialDensityWithoutJaggedness().mapAll(plain);
		climate = new Climate.Sampler(router.temperature().mapAll(plain), router.vegetation().mapAll(plain), router.continents().mapAll(plain),
				router.erosion().mapAll(plain), router.depth().mapAll(plain), router.ridges().mapAll(plain), settings.spawnTarget());
	}

	private static Object kind(DensityFunction marker) {
		return ((DensityFunctions.MarkerOrMarked) marker).type();
	}

	/** The shaping to buildings added from here on (null: none). */
	void shapeTo(@Nullable Beardifier beard) {
		this.beard = beard;
	}

	/** The final density at a block, as the generator has it. */
	double exact(int x, int y, int z) {
		Point p = point.set(x, y, z);
		double d = exact.compute(p);
		return beard == null ? d : d + beard.compute(p);
	}

	/** The final density at a block, its cells' interpolation left out. */
	double coarse(int x, int y, int z) {
		Point p = point.set(x, y, z);
		double d = coarse.compute(p);
		return beard == null ? d : d + beard.compute(p);
	}

	/**
	 * The generator's preliminary surface at block x, z, as it finds it: the first of its cells' corners from the top down
	 * where the density without the jaggedness is over {@link #SURFACE}; {@link Integer#MAX_VALUE} if there's none.
	 */
	int preliminarySurface(int x, int z) {
		int qx = x & ~3, qz = z & ~3;
		for (int y = minY + height; y >= minY; y -= cellHeight) {
			if (initial.compute(point.set(qx, y, qz)) > SURFACE) return y;
		}
		return Integer.MAX_VALUE;
	}

	/** Swaps the game's markers for what NoiseChunk makes of them (with the cells' interpolation, or without it). */
	private final class Visitor implements DensityFunction.Visitor {
		private final Map<DensityFunction, DensityFunction> made;
		private final boolean interpolating;
		private final Map<DensityFunction, DensityFunction> interpolated = new HashMap<>();

		Visitor(Map<DensityFunction, DensityFunction> made, boolean interpolating) {
			this.made = made;
			this.interpolating = interpolating;
		}

		@Override
		public DensityFunction apply(DensityFunction function) {
			if (function instanceof DensityFunctions.MarkerOrMarked marker) {
				Object kind = marker.type();
				DensityFunction wrapped = marker.wrapped();
				if (kind == INTERPOLATED) return interpolating ? interpolated.computeIfAbsent(function, f -> new Interpolated(wrapped)) : wrapped;
				if (kind == FLAT_CACHE) return made.computeIfAbsent(function, f -> new FlatCache(wrapped));
				if (kind == CACHE_2D) return made.computeIfAbsent(function, f -> new Cache2D(wrapped));
				// The other caches only save work in a chunk: the same values.
				return wrapped;
			}
			return made.computeIfAbsent(function, f -> f);
		}
	}

	/** A block to work a function out at, reused. */
	private static final class Point implements DensityFunction.FunctionContext {
		private int x, y, z;

		Point set(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
			return this;
		}

		@Override
		public int blockX() {
			return x;
		}

		@Override
		public int blockY() {
			return y;
		}

		@Override
		public int blockZ() {
			return z;
		}
	}

	/** The values a cache keeps, by key ({@link Long#MIN_VALUE}: none). */
	private static final class Kept {
		private final long[] keys = new long[KEPT];
		private final double[] values = new double[KEPT];

		Kept() {
			Arrays.fill(keys, Long.MIN_VALUE);
		}

		static int slot(long key) {
			return (int) HashCommon.mix(key) & KEPT - 1;
		}
	}

	/** Never written out. */
	private interface Made extends DensityFunction.SimpleFunction {
		@Override
		default KeyDispatchDataCodec<? extends DensityFunction> codec() {
			throw new UnsupportedOperationException("Far land density functions aren't saved");
		}
	}

	/**
	 * Between the corners of the cell a block is in, interpolated as the generator interpolates its final density
	 * (NoiseChunk.NoiseInterpolator while it fills a cell); each corner worked out once.
	 */
	private final class Interpolated implements Made {
		private final DensityFunction corners;
		private final Point at = new Point();
		private final Kept kept = new Kept();
		/** The last cell's corners. */
		private long cell = Long.MIN_VALUE;
		private double n000, n100, n010, n110, n001, n101, n011, n111;

		Interpolated(DensityFunction corners) {
			this.corners = corners;
		}

		@Override
		public double compute(FunctionContext context) {
			int x = context.blockX(), y = context.blockY(), z = context.blockZ();
			int cx = Math.floorDiv(x, cellWidth), cy = Math.floorDiv(y, cellHeight), cz = Math.floorDiv(z, cellWidth);
			long key = (long) (cx & 0xFFFFFF) << 40 | (long) (cz & 0xFFFFFF) << 16 | cy & 0xFFFF;
			if (key != cell) {
				cell = key;
				n000 = corner(cx, cy, cz);
				n100 = corner(cx + 1, cy, cz);
				n010 = corner(cx, cy + 1, cz);
				n110 = corner(cx + 1, cy + 1, cz);
				n001 = corner(cx, cy, cz + 1);
				n101 = corner(cx + 1, cy, cz + 1);
				n011 = corner(cx, cy + 1, cz + 1);
				n111 = corner(cx + 1, cy + 1, cz + 1);
			}
			return Mth.lerp3((double) (x - cx * cellWidth) / cellWidth, (double) (y - cy * cellHeight) / cellHeight, (double) (z - cz * cellWidth) / cellWidth,
					n000, n100, n010, n110, n001, n101, n011, n111);
		}

		private double corner(int cx, int cy, int cz) {
			long key = (long) (cx & 0xFFFFFF) << 40 | (long) (cz & 0xFFFFFF) << 16 | cy & 0xFFFF;
			int i = Kept.slot(key);
			if (kept.keys[i] == key) return kept.values[i];
			double value = corners.compute(at.set(cx * cellWidth, cy * cellHeight, cz * cellWidth));
			kept.keys[i] = key;
			kept.values[i] = value;
			return value;
		}

		@Override
		public double minValue() {
			return corners.minValue();
		}

		@Override
		public double maxValue() {
			return corners.maxValue();
		}
	}

	/** NoiseChunk.FlatCache: the value at the corner of the 4-block square a block is in, at y 0, once a square. */
	private static final class FlatCache implements Made {
		private final DensityFunction wrapped;
		private final Point at = new Point();
		private final Kept kept = new Kept();

		FlatCache(DensityFunction wrapped) {
			this.wrapped = wrapped;
		}

		@Override
		public double compute(FunctionContext context) {
			int x = context.blockX() & ~3, z = context.blockZ() & ~3;
			long key = (long) x << 32 | z & 0xFFFFFFFFL;
			int i = Kept.slot(key);
			if (kept.keys[i] == key) return kept.values[i];
			double value = wrapped.compute(at.set(x, 0, z));
			kept.keys[i] = key;
			kept.values[i] = value;
			return value;
		}

		@Override
		public double minValue() {
			return wrapped.minValue();
		}

		@Override
		public double maxValue() {
			return wrapped.maxValue();
		}
	}

	/** NoiseChunk.Cache2D: once a column. */
	private static final class Cache2D implements Made {
		private final DensityFunction wrapped;
		private final Kept kept = new Kept();

		Cache2D(DensityFunction wrapped) {
			this.wrapped = wrapped;
		}

		@Override
		public double compute(FunctionContext context) {
			long key = (long) context.blockX() << 32 | context.blockZ() & 0xFFFFFFFFL;
			int i = Kept.slot(key);
			if (kept.keys[i] == key) return kept.values[i];
			double value = wrapped.compute(context);
			kept.keys[i] = key;
			kept.values[i] = value;
			return value;
		}

		@Override
		public double minValue() {
			return wrapped.minValue();
		}

		@Override
		public double maxValue() {
			return wrapped.maxValue();
		}
	}
}
