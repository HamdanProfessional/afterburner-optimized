package com.afterburner.labs.worldgen;

import com.afterburner.Afterburner;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Buildings for the far land ({@link FarLand}): the game's own structures over the ground (villages, temples, outposts,
 * huts, igloos, mansions, ruined portals, shipwrecks; not what's under it: mines, strongholds, ancient cities), started
 * where the game starts them (the same choice of chunks and kinds, the same pieces) and put down chunk by chunk as the
 * game puts them down, into the far trees' stand-in world before the trees. The ground is shaped to them as the game
 * shapes it (villages and outposts: {@link #shaping}).
 */
public final class FarStructures {
	/** {@code -Dafterburner.farStructures=false}: far land without buildings. */
	public static final boolean ALLOWED = !"false".equals(System.getProperty("afterburner.farStructures"));
	/** {@code -Dafterburner.farBeard=false}: the far land's ground isn't shaped to the buildings. */
	public static final boolean BEARD = !"false".equals(System.getProperty("afterburner.farBeard"));
	/** How far (in blocks) the game shapes the ground around a building's pieces. */
	private static final int BEARD_REACH = 12;
	/** How far (in chunks) a structure reaches from the chunk it starts in, as the game looks for them. */
	private static final int REACH = 8;
	private static final int KEPT = 4096, FAILURES = 16;

	private final ServerLevel level;
	private final ChunkGenerator generator;
	private final ChunkGeneratorStructureState state;
	private final int step;
	private final List<Holder<StructureSet>> sets = new ArrayList<>();
	/** Per structure of the step (by identity): its place among them, which the game seeds it by. */
	private final Map<Structure, Integer> index = new IdentityHashMap<>();
	/** Per set and spot (see {@link #key}): its start, {@link StructureStart#INVALID_START} for none. Any thread's. */
	private final Long2ObjectLinkedOpenHashMap<StructureStart> starts = new Long2ObjectLinkedOpenHashMap<>();
	private final Map<Structure, int[]> failures = new IdentityHashMap<>();

	private FarStructures(ServerLevel level, ChunkGenerator generator) {
		this.level = level;
		this.generator = generator;
		this.state = level.getChunkSource().getGeneratorState();
		this.step = GenerationStep.Decoration.SURFACE_STRUCTURES.ordinal();
		// As the game numbers them: the registry's structures of each step, in order.
		int n = 0;
		for (Structure structure : level.registryAccess().lookupOrThrow(Registries.STRUCTURE)) {
			if (structure.step().ordinal() == step) index.put(structure, n++);
		}
		for (Holder<StructureSet> set : state.possibleStructureSets()) {
			if (set.value().structures().stream().anyMatch(e -> index.containsKey(e.structure().value()))) sets.add(set);
		}
		for (Structure structure : index.keySet()) failures.put(structure, new int[1]);
	}

	/** Null if there are none or they can't be read (logged). */
	static @Nullable FarStructures of(ServerLevel level, ChunkGenerator generator) {
		if (!ALLOWED) return null;
		try {
			FarStructures structures = new FarStructures(level, generator);
			return structures.sets.isEmpty() ? null : structures;
		} catch (RuntimeException e) {
			Afterburner.LOGGER.error("[Afterburner] Far land: couldn't read the world's structures; the far land is made without them", e);
			return null;
		}
	}

	/** A structure start and its place in the step. */
	public record Placed(StructureStart start, int index) {
	}

	/** The starts (of the step) whose pieces reach into chunk {@code cx}, {@code cz}, in the order the game puts them down. */
	public List<Placed> touching(int cx, int cz) {
		List<Placed> out = new ArrayList<>(2);
		int x0 = cx << 4, z0 = cz << 4;
		for (int s = 0; s < sets.size(); s++) {
			StructurePlacement placement = sets.get(s).value().placement();
			if (placement instanceof RandomSpreadStructurePlacement spread) {
				// One spot a spacing-wide square at most: only those can start here.
				int spacing = spread.spacing();
				for (int rz = Math.floorDiv(cz - REACH, spacing); rz <= Math.floorDiv(cz + REACH, spacing); rz++) {
					for (int rx = Math.floorDiv(cx - REACH, spacing); rx <= Math.floorDiv(cx + REACH, spacing); rx++) {
						ChunkPos spot = spread.getPotentialStructureChunk(state.getLevelSeed(), rx * spacing, rz * spacing);
						if (Math.abs(spot.x() - cx) > REACH || Math.abs(spot.z() - cz) > REACH) continue;
						add(out, start(s, spot.x(), spot.z()), x0, z0);
					}
				}
			} else {
				for (int sz = cz - REACH; sz <= cz + REACH; sz++) {
					for (int sx = cx - REACH; sx <= cx + REACH; sx++) add(out, start(s, sx, sz), x0, z0);
				}
			}
		}
		out.sort((a, b) -> Integer.compare(a.index(), b.index()));
		return out;
	}

	/**
	 * How the game shapes the ground to buildings (its {@link Beardifier}), and the heights it can change the ground
	 * between ({@code lo} to {@code hi}, empty when lo > hi): up to {@link #BEARD_REACH} above and below the pieces and
	 * where they join.
	 */
	public record Shaping(Beardifier beardifier, int lo, int hi) {
		public static final Shaping NONE = new Shaping(Beardifier.EMPTY, Integer.MAX_VALUE, Integer.MIN_VALUE);
	}

	/**
	 * How the game shapes the ground to the buildings (their terrain adaptation: villages and outposts) for the blocks from
	 * minX, minZ to maxX, maxZ, as it works it out for a chunk (Beardifier.forStructuresInChunk) but for the whole area:
	 * every piece of the starts there within {@link #BEARD_REACH} of it, and where pieces join. A spot's shaping only
	 * comes from pieces that close, so it's the game's for any spot in the area.
	 */
	public Shaping shaping(int minX, int minZ, int maxX, int maxZ) {
		Set<StructureStart> starts = new ReferenceOpenHashSet<>();
		for (int s = 0; s < sets.size(); s++) {
			StructurePlacement placement = sets.get(s).value().placement();
			int cx0 = (minX >> 4) - REACH, cz0 = (minZ >> 4) - REACH, cx1 = (maxX >> 4) + REACH, cz1 = (maxZ >> 4) + REACH;
			if (placement instanceof RandomSpreadStructurePlacement spread) {
				int spacing = spread.spacing();
				for (int rz = Math.floorDiv(cz0, spacing); rz <= Math.floorDiv(cz1, spacing); rz++) {
					for (int rx = Math.floorDiv(cx0, spacing); rx <= Math.floorDiv(cx1, spacing); rx++) {
						ChunkPos spot = spread.getPotentialStructureChunk(state.getLevelSeed(), rx * spacing, rz * spacing);
						if (spot.x() < cx0 || spot.x() > cx1 || spot.z() < cz0 || spot.z() > cz1) continue;
						starts.add(start(s, spot.x(), spot.z()));
					}
				}
			} else {
				for (int sz = cz0; sz <= cz1; sz++) {
					for (int sx = cx0; sx <= cx1; sx++) starts.add(start(s, sx, sz));
				}
			}
		}
		List<Beardifier.Rigid> rigids = new ArrayList<>();
		List<JigsawJunction> junctions = new ArrayList<>();
		BoundingBox any = null;
		int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
		for (StructureStart start : starts) {
			// The start's box is already widened by the shaping's reach for these.
			if (!start.isValid() || !start.getBoundingBox().intersects(minX, minZ, maxX, maxZ)) continue;
			TerrainAdjustment adjustment = start.getStructure().terrainAdaptation();
			if (adjustment == TerrainAdjustment.NONE) continue;
			synchronized (start) {
				for (StructurePiece piece : start.getPieces()) {
					BoundingBox box = piece.getBoundingBox();
					if (!box.intersects(minX - BEARD_REACH, minZ - BEARD_REACH, maxX + BEARD_REACH, maxZ + BEARD_REACH)) continue;
					if (piece instanceof PoolElementStructurePiece pool) {
						if (pool.getElement().getProjection() == StructureTemplatePool.Projection.RIGID) {
							rigids.add(new Beardifier.Rigid(box, adjustment, pool.getGroundLevelDelta()));
							any = any == null ? box : BoundingBox.encapsulating(any, box);
							int ground = box.minY() + pool.getGroundLevelDelta();
							lo = Math.min(lo, Math.min(box.minY(), ground) - BEARD_REACH);
							hi = Math.max(hi, Math.max(box.maxY(), ground) + BEARD_REACH);
						}
						for (JigsawJunction junction : pool.getJunctions()) {
							int x = junction.getSourceX(), z = junction.getSourceZ();
							if (x <= minX - BEARD_REACH || z <= minZ - BEARD_REACH || x >= maxX + BEARD_REACH || z >= maxZ + BEARD_REACH) continue;
							junctions.add(junction);
							BoundingBox at = new BoundingBox(new BlockPos(x, junction.getSourceGroundY(), z));
							any = any == null ? at : BoundingBox.encapsulating(any, at);
							lo = Math.min(lo, junction.getSourceGroundY() - BEARD_REACH);
							hi = Math.max(hi, junction.getSourceGroundY() + BEARD_REACH);
						}
					} else {
						rigids.add(new Beardifier.Rigid(box, adjustment, 0));
						any = any == null ? box : BoundingBox.encapsulating(any, box);
						lo = Math.min(lo, box.minY() - BEARD_REACH);
						hi = Math.max(hi, box.maxY() + BEARD_REACH);
					}
				}
			}
		}
		return any == null ? Shaping.NONE
				: new Shaping(new Beardifier(List.copyOf(rigids), List.copyOf(junctions), any.inflatedBy(24)), lo, hi);
	}

	private void add(List<Placed> out, StructureStart start, int x0, int z0) {
		if (!start.isValid() || !start.getBoundingBox().intersects(x0, z0, x0 + 15, z0 + 15)) return;
		Integer i = index.get(start.getStructure());
		if (i != null) out.add(new Placed(start, i));
	}

	private static long key(int set, int x, int z) {
		return (long) set << 48 ^ (long) (x & 0xFFFFFF) << 24 ^ z & 0xFFFFFF;
	}

	/** Set {@code s}'s start in chunk {@code x}, {@code z}, as the game's createStructures makes it (kept). */
	private StructureStart start(int s, int x, int z) {
		long key = key(s, x, z);
		synchronized (starts) {
			StructureStart kept = starts.getAndMoveToFirst(key);
			if (kept != null) return kept;
		}
		StructureStart made = make(sets.get(s).value(), x, z);
		synchronized (starts) {
			starts.putAndMoveToFirst(key, made);
			if (starts.size() > KEPT) starts.removeLast();
		}
		return made;
	}

	private StructureStart make(StructureSet set, int x, int z) {
		if (!set.placement().isStructureChunk(state, x, z)) return StructureStart.INVALID_START;
		ChunkPos chunk = new ChunkPos(x, z);
		Climate.Sampler climate = state.randomState().createClimateSampler(SamplerContext.builder().enableCaches().build());
		List<StructureSet.StructureSelectionEntry> options = new ArrayList<>(set.structures());
		try {
			if (options.size() == 1) return generate(options.getFirst(), chunk, climate);
			WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
			random.setLargeFeatureSeed(state.getLevelSeed(), x, z);
			int total = 0;
			for (StructureSet.StructureSelectionEntry option : options) total += option.weight();
			while (!options.isEmpty()) {
				int choice = random.nextInt(total), i = 0;
				for (StructureSet.StructureSelectionEntry option : options) {
					choice -= option.weight();
					if (choice < 0) break;
					i++;
				}
				StructureSet.StructureSelectionEntry selected = options.get(i);
				StructureStart start = generate(selected, chunk, climate);
				if (start.isValid()) return start;
				options.remove(i);
				total -= selected.weight();
			}
		} catch (RuntimeException e) {
			Afterburner.LOGGER.warn("[Afterburner] Far land: a structure couldn't be started in the far land at chunk {}, {}", x, z, e);
		}
		return StructureStart.INVALID_START;
	}

	private StructureStart generate(StructureSet.StructureSelectionEntry selected, ChunkPos chunk, Climate.Sampler climate) {
		Structure structure = selected.structure().value();
		return structure.generate(selected.structure(), level.dimension(), level.registryAccess(), generator, generator.getBiomeSource(), climate,
				state.randomState(), level.getStructureTemplateManager(), state.getLevelSeed(), chunk, 0, level, structure.biomes()::contains);
	}

	/**
	 * Puts down the parts in chunk {@code cx}, {@code cz} of the starts touching it, as the game does when it decorates
	 * the chunk: each structure seeded by its place, cut to the chunk.
	 */
	void place(WorldGenLevel world, WorldgenRandom random, long decorationSeed, int cx, int cz, List<Placed> touching) {
		BoundingBox box = new BoundingBox(cx << 4, level.getMinY() + 1, cz << 4, (cx << 4) + 15, level.getMaxY(), (cz << 4) + 15);
		ChunkPos chunk = new ChunkPos(cx, cz);
		int last = -1;
		for (Placed placed : touching) {
			Structure structure = placed.start().getStructure();
			int[] failed = failures.get(structure);
			if (failed != null && failed[0] >= FAILURES) continue;
			if (placed.index() != last) random.setFeatureSeed(decorationSeed, placed.index(), step);
			last = placed.index();
			// Some pieces remember things the first time they're put down (how high the ground is), and the starts are
			// every sampler's: one at a time.
			try {
				synchronized (placed.start()) {
					placed.start().placeInChunk(world, level.structureManager(), generator, random, box, chunk);
				}
			} catch (RuntimeException e) {
				if (failed == null) continue;
				synchronized (failed) {
					if (failed[0]++ == 0) Afterburner.LOGGER.warn("[Afterburner] Far land: a structure failed in the far land: {}", structure, e);
					if (failed[0] == FAILURES) Afterburner.LOGGER.warn("[Afterburner] Far land: that structure is left out from now on");
				}
			}
		}
	}

	/** For the check command: where a start's pieces stand. */
	public static boolean covers(StructureStart start, BlockPos pos) {
		return start.getPieces().stream().anyMatch(p -> p.getBoundingBox().isInside(pos));
	}
}
