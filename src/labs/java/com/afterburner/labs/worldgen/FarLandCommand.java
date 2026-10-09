package com.afterburner.labs.worldgen;

import com.afterburner.Afterburner;
import com.afterburner.bench.ChunkCommand;
import com.afterburner.bench.Reports;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /afterburner-farland [radius] [x z]}: checks the far land made from the seed ({@link FarLand}) against the
 * real thing. Loads a square of chunks (default radius 3 around chunk 0, 0) and compares, column by column, the ground's
 * height with the generator's own (before caves are carved and trees grow) and the top block with the chunk's, and the
 * trees (where they stand, how high) and the buildings (which start in the square, how high they stand); then the
 * coarser levels' heights, and how long a sheet takes at each level. Op only.
 */
public final class FarLandCommand {
	private static boolean running;

	private FarLandCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context,
			Commands.CommandSelection selection) {
		dispatcher.register(Commands.literal("afterburner-farland")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(c -> run(c.getSource(), 3, 0, 0))
				.then(Commands.argument("radius", IntegerArgumentType.integer(0, 16))
						.executes(c -> run(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), 0, 0))
						.then(Commands.argument("x", IntegerArgumentType.integer())
								.then(Commands.argument("z", IntegerArgumentType.integer())
										.executes(c -> run(c.getSource(), IntegerArgumentType.getInteger(c, "radius"),
												IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z")))))));
	}

	private static int run(CommandSourceStack source, int radius, int cx, int cz) {
		if (running) {
			source.sendFailure(Component.literal("A far land check is already running."));
			return 0;
		}
		ServerLevel level = source.getLevel();
		FarLand land = FarLand.of(level);
		if (land == null) {
			source.sendFailure(Component.literal("Far land check failed: this dimension's generator can't make far land."));
			return 0;
		}
		running = true;
		MinecraftServer server = source.getServer();
		ServerChunkCache chunks = level.getChunkSource();
		List<ChunkPos> area = new ArrayList<>();
		List<CompletableFuture<?>> loads = new ArrayList<>();
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				ChunkPos pos = new ChunkPos(cx + dx, cz + dz);
				area.add(pos);
				loads.add(chunks.addTicketAndLoadWithRadius(ChunkCommand.TICKET, pos, 0));
			}
		}
		source.sendSuccess(() -> Component.literal("Loading " + area.size() + " chunks for the far land check...").withStyle(ChatFormatting.GRAY), true);
		CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).whenCompleteAsync((ignored, error) -> {
			try {
				if (error != null) throw new RuntimeException(error);
				List<String> lines = check(level, land, area, cx, cz);
				String saved = Reports.write("farland", lines);
				source.sendSuccess(() -> Component.literal("Far land check done").withStyle(ChatFormatting.GOLD), true);
				for (String line : lines) source.sendSuccess(() -> Component.literal(line), true);
				if (saved != null) source.sendSuccess(() -> Component.literal("Saved to " + saved).withStyle(ChatFormatting.GRAY), true);
				Afterburner.LOGGER.info("Far land check: {}", String.join(" | ", lines));
			} catch (RuntimeException e) {
				source.sendFailure(Component.literal("Far land check failed: " + e));
				Afterburner.LOGGER.error("Far land check failed", e);
			} finally {
				for (ChunkPos pos : area) chunks.removeTicketWithRadius(ChunkCommand.TICKET, pos, 0);
				running = false;
			}
		}, server);
		return 1;
	}

	private static List<String> check(ServerLevel level, FarLand land, List<ChunkPos> area, int cx, int cz) {
		ServerChunkCache chunks = level.getChunkSource();
		FarLand.Sampler sampler = land.sampler();
		// The far land's voxels start at its own bottom (the generator's, if that's above the level's).
		int minY = land.minY, seaLevel = level.getSeaLevel();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		long columns = 0, counted = 0, exact = 0, near = 0, absSum = 0, tops = 0, topsSame = 0, nanos = 0;
		// Level 0's ground (exact, shaped to buildings), by column, to check the further levels against.
		Map<Long, Integer> madeGround = new HashMap<>();
		List<String> boxExamples = new ArrayList<>();
		int[] fromPrelim = new int[200];
		long boxDiffers = 0, underAir = 0, underAirSame = 0, underSolid = 0, underSolidSame = 0;
		int worst = 0;
		Map<String, Integer> wrong = new HashMap<>();
		long realTrees = 0, madeTrees = 0, bothTrees = 0, treeOff = 0, treeNear = 0, treeNanos = 0;
		// Buildings: those (over the ground) starting in the square, real and made, and where the real ones' pieces stand.
		Set<String> realStarts = new HashSet<>(), madeStarts = new HashSet<>();
		List<BoundingBox> realPieces = new ArrayList<>();
		// The pieces of those the ground is shaped to (villages, outposts), widened by how far the shaping reaches.
		List<BoundingBox> shaping = new ArrayList<>();
		Set<ChunkPos> inArea = new HashSet<>(area);
		FarStructures structures = land.structures();
		for (ChunkPos chunkPos : area) {
			for (StructureStart start : level.getChunk(chunkPos.x(), chunkPos.z()).getAllStarts().values()) {
				if (!start.isValid() || start.getStructure().step() != GenerationStep.Decoration.SURFACE_STRUCTURES) continue;
				realStarts.add(name(level, start));
				for (StructurePiece piece : start.getPieces()) {
					realPieces.add(piece.getBoundingBox());
					if (start.getStructure().terrainAdaptation() != TerrainAdjustment.NONE) shaping.add(piece.getBoundingBox().inflatedBy(12));
				}
			}
			if (structures == null) continue;
			for (FarStructures.Placed placed : structures.touching(chunkPos.x(), chunkPos.z())) {
				if (inArea.contains(placed.start().getChunkPos())) madeStarts.add(name(level, placed.start()));
			}
		}
		long built = 0, builtReal = 0, builtMade = 0, builtNear = 0, builtOff = 0;
		long shaped = 0, shapedNear = 0, shapedOff = 0, shapedPlain = 0;
		for (ChunkPos chunkPos : area) {
			long t0 = System.nanoTime(), tree0 = sampler.treeNanos;
			FarLand.Sheet sheet = sampler.sheet(0, chunkPos.getMinBlockX(), chunkPos.getMinBlockZ());
			nanos += System.nanoTime() - t0;
			treeNanos += sampler.treeNanos - tree0;
			// The box way after (it'd find this one's samples cached otherwise), to compare.
			sampler.searchColumns = false;
			FarLand.Sheet box = sampler.sheet(0, chunkPos.getMinBlockX(), chunkPos.getMinBlockZ());
			sampler.searchColumns = true;
			for (int c = 0; c < 256; c++) {
				if (box.ground[c] == sheet.ground[c]) continue;
				boxDiffers++;
				if (boxExamples.size() < 3) {
					boxExamples.add((chunkPos.getMinBlockX() + (c & 15)) + " " + (chunkPos.getMinBlockZ() + (c >> 4)) + ": box " + box.ground[c] + ", column " + sheet.ground[c]);
				}
			}
			LevelChunk chunk = level.getChunk(chunkPos.x(), chunkPos.z());
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					int c = z * 16 + x, bx = chunkPos.getMinBlockX() + x, bz = chunkPos.getMinBlockZ() + z;
					int real = chunks.getGenerator().getBaseHeight(bx, bz, Heightmap.Types.OCEAN_FLOOR_WG, level, chunks.randomState());
					int mine = sheet.ground[c], diff = Math.abs(real - mine);
					madeGround.put(column(bx, bz), mine);
					// Where the ground's shaped to buildings, the generator's height (unshaped) isn't the real one: the
					// buildings' lines below check those.
					boolean byBuildings = covered(shaping, bx, bz);
					fromPrelim[Math.clamp(real - Math.round(sheet.prelim[c]) + 100, 0, 199)]++;
					// Trees: the top of the highest leaves or log over the ground, real against made.
					int realTree = realTreeTop(chunk, pos, bx, bz, real), madeTree = madeTreeTop(sheet, c, minY);
					if (realTree > 0) realTrees++;
					if (madeTree > 0) madeTrees++;
					if (realTree > 0 && madeTree > 0) {
						bothTrees++;
						treeOff += Math.abs(realTree - madeTree);
						if (Math.abs(realTree - madeTree) <= 1) treeNear++;
					}
					// By a building the ground is shaped to, not under it: the real ground's top (not trees, plants, snow), made
					// and as the generator has it unshaped.
					if (!covered(realPieces, bx, bz) && covered(shaping, bx, bz)) {
						int ground = realGround(chunk, pos, bx, bz, minY);
						shaped++;
						shapedOff += Math.abs(ground - mine);
						if (Math.abs(ground - mine) <= 1) shapedNear++;
						if (Math.abs(ground - real) <= 1) shapedPlain++;
					}
					// Under a real building's pieces: how high the top of what stands there is (not trees), real and made.
					if (covered(realPieces, bx, bz)) {
						int realTop = realBuildingTop(chunk, pos, bx, bz, real), madeTop = madeBuildingTop(sheet, c, minY, mine);
						built++;
						if (realTop > real) builtReal++;
						if (madeTop > mine) builtMade++;
						builtOff += Math.abs(realTop - madeTop);
						if (Math.abs(realTop - madeTop) <= 1) builtNear++;
					}
					columns++;
					if (!byBuildings) {
						counted++;
						absSum += diff;
						worst = Math.max(worst, diff);
						if (diff == 0) exact++;
						if (diff <= 1) near++;
					}
					// Under the ground's top, from a little below the preliminary surface: air (under a floating island, an
					// arch; caves too, which aren't made) and ground, block by block.
					for (int y = Math.max(minY, Math.round(sheet.prelim[c]) - 8), to = Math.min(real, mine); y < to; y++) {
						BlockState b = chunk.getBlockState(pos.set(bx, y, bz));
						boolean madeOpen = open(sheet, c, y - minY);
						if (b.isAir() || b.getBlock() instanceof LiquidBlock) {
							underAir++;
							if (madeOpen) underAirSame++;
						} else {
							underSolid++;
							if (!madeOpen) underSolidSame++;
						}
					}
					if (byBuildings || diff != 0 || mine <= seaLevel) continue;
					BlockState made = state(sheet, c, mine - 1 - minY);
					BlockState found = chunk.getBlockState(pos.set(bx, mine - 1, bz));
					tops++;
					if (made != null && made.getBlock() == found.getBlock()) {
						topsSame++;
					} else {
						wrong.merge(name(found) + " made " + (made == null ? "nothing" : name(made)), 1, Integer::sum);
					}
				}
			}
		}
		List<String> lines = new ArrayList<>();
		lines.add("Level 0 around chunk " + cx + ", " + cz + ": " + columns + " columns (" + (columns - counted) + " by buildings left out), ground height exact in "
				+ pct(exact, counted) + ", within 1 block in " + pct(near, counted) + ", mean off " + Reports.f2((double) absSum / Math.max(1, counted)) + ", worst " + worst
				+ "; " + Reports.f2(nanos / 1e6 / area.size()) + " ms a sheet; the box way differs in " + boxDiffers + " columns"
				+ (boxExamples.isEmpty() ? "" : " (" + String.join("; ", boxExamples) + ")"));
		lines.add("Ground minus preliminary surface: " + spread(fromPrelim, columns));
		lines.add("Under the ground's top (from 8 below the preliminary surface): " + underAir + " blocks of air, made air in "
				+ pct(underAirSame, underAir) + "; " + underSolid + " of ground, made ground in " + pct(underSolidSame, underSolid));
		List<Map.Entry<String, Integer>> worstTops = new ArrayList<>(wrong.entrySet());
		worstTops.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
		StringBuilder top = new StringBuilder("Top block (land, right height) same in " + pct(topsSame, tops) + " of " + tops);
		for (int i = 0; i < Math.min(5, worstTops.size()); i++) top.append(i == 0 ? "; most wrong: " : ", ").append(worstTops.get(i).getKey()).append(" x").append(worstTops.get(i).getValue());
		lines.add(top.toString());
		lines.add("Trees (level 0): in " + realTrees + " columns, made in " + madeTrees + "; made where real in " + pct(bothTrees, realTrees)
				+ ", real where made in " + pct(bothTrees, madeTrees) + "; both: top within 1 block in " + pct(treeNear, bothTrees) + ", mean off "
				+ (bothTrees == 0 ? "-" : Reports.f2((double) treeOff / bothTrees)) + "; " + Reports.f2(treeNanos / 1e6 / area.size()) + " ms of trees a sheet");
		Set<String> both = new HashSet<>(realStarts);
		both.retainAll(madeStarts);
		lines.add("Buildings: starting in the square " + realStarts.size() + " real, " + madeStarts.size() + " made, " + both.size() + " the same"
				+ (realStarts.isEmpty() ? "" : " (" + String.join(", ", realStarts) + ")") + "; under their pieces " + built + " columns, something over the ground in "
				+ builtReal + " real, " + builtMade + " made; top within 1 block in " + pct(builtNear, built) + ", mean off "
				+ (built == 0 ? "-" : Reports.f2((double) builtOff / built)));
		lines.add("Ground by buildings it's shaped to (within 12 blocks of a piece, not under one): " + shaped + " columns, made within 1 block in "
				+ pct(shapedNear, shaped) + " (the generator's unshaped height in " + pct(shapedPlain, shaped) + "), mean off "
				+ (shaped == 0 ? "-" : Reports.f2((double) shapedOff / shaped)) + (FarStructures.BEARD ? "" : " (shaping off)"));
		for (int lv = 1; lv <= 4; lv++) {
			int s = 1 << lv, span = 16 << lv;
			int x0 = Math.floorDiv(cx * 16, span) * span, z0 = Math.floorDiv(cz * 16, span) * span;
			StringBuilder line = new StringBuilder("Level " + lv + ":");
			for (int mode = 0; mode < 2; mode++) {
				sampler.searchColumns = mode == 1;
				FarLand.Sheet sheet = sampler.sheet(lv, x0, z0);
				long n = 0, sum = 0, within = 0;
				for (int c = 0; c < 256; c++) {
					int bx = x0 + (c & 15) * s, bz = z0 + (c >> 4) * s;
					// Level 0's ground where it was made, else the generator's (not by buildings: that's unshaped).
					Integer exactGround = madeGround.get(column(bx, bz));
					if (exactGround == null && covered(shaping, bx, bz)) continue;
					int real = exactGround != null ? exactGround
							: chunks.getGenerator().getBaseHeight(bx, bz, Heightmap.Types.OCEAN_FLOOR_WG, level, chunks.randomState());
					int diff = Math.abs(real - sheet.ground[c]);
					n++;
					sum += diff;
					if (diff <= 2) within++;
				}
				line.append(mode == 0 ? " box" : ", by column").append(" ground height within 2 blocks of level 0's in ")
						.append(pct(within, n)).append(", mean off ").append(Reports.f2((double) sum / n));
			}
			sampler.searchColumns = true;
			lines.add(line.toString());
		}
		// Just past the checked square (the same kind of land, but no sheets made there yet): how long a sheet takes at
		// each level.
		StringBuilder times = new StringBuilder("Time a sheet, new land:");
		FarLand.Sampler boxSampler = land.sampler();
		boxSampler.searchColumns = false;
		for (int lv = 0; lv <= 4; lv++) {
			int span = 16 << lv, reach = (int) Math.sqrt(area.size()) / 2 + 2;
			int baseX = Math.floorDiv((cx << 4) - 5 * span, span) * span, baseZ = Math.floorDiv(cz + reach << 4, span) * span;
			sampler.sheet(lv, baseX, baseZ);
			{
				// The box way and the column way on the same sheets, taking turns, each with its own sampler (one would find
				// the other's samples cached).
				long box = 0, column = 0, boxSamples = 0, columnSamples = 0;
				boxSampler.sheet(lv, baseX, baseZ);
				for (int i = 1; i <= 8; i++) {
					for (int mode = 0; mode < 2; mode++) {
						FarLand.Sampler which = mode == 0 ? boxSampler : sampler;
						long g = which.groundNanos, n = which.samples;
						which.sheet(lv, baseX + i * span, baseZ + span);
						if (mode == 0) {
							box += which.groundNanos - g;
							boxSamples += which.samples - n;
						} else {
							column += which.groundNanos - g;
							columnSamples += which.samples - n;
						}
					}
				}
				times.append(" [L").append(lv).append(" ground: box ").append(Reports.f2(box / 8e6)).append(" ms ").append(boxSamples / 8)
						.append(" samples, by column ").append(Reports.f2(column / 8e6)).append(" ms ").append(columnSamples / 8).append(" samples]");
			}
			long t0 = System.nanoTime(), g0 = sampler.groundNanos, s0 = sampler.surfaceNanos, n0 = sampler.samples;
			long gs0 = sampler.gapSamples, gc0 = sampler.gapColumns, tr0 = sampler.treeNanos, tc0 = sampler.treeChunks, tv0 = sampler.treeVoxelCount;
			long b0 = sampler.builtChunks();
			for (int i = 1; i <= 8; i++) sampler.sheet(lv, baseX + i * span, baseZ);
			times.append(" L").append(lv).append(' ').append(Reports.f2((System.nanoTime() - t0) / 8e6)).append(" ms (ground ")
					.append(Reports.f2((sampler.groundNanos - g0) / 8e6)).append(", surface ").append(Reports.f2((sampler.surfaceNanos - s0) / 8e6))
					.append(", trees ").append(Reports.f2((sampler.treeNanos - tr0) / 8e6)).append(" for ").append(Reports.f1((sampler.treeChunks - tc0) / 8.0))
					.append(" chunks (").append(Reports.f1((sampler.builtChunks() - b0) / 8.0)).append(" with buildings), ")
					.append(Reports.f1((sampler.treeVoxelCount - tv0) / 8.0)).append(" tree voxels, ")
					.append((sampler.samples - n0) / 8).append(" samples, ").append((sampler.gapSamples - gs0) / 8).append(" of them under the ground, ")
					.append(Reports.f1((sampler.gapColumns - gc0) / 8.0)).append(" columns with air under it)");
		}
		lines.add(times.toString());
		return lines;
	}

	private static boolean tree(BlockState state) {
		return state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || state.getBlock() instanceof HugeMushroomBlock;
	}

	private static String name(ServerLevel level, StructureStart start) {
		return level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(start.getStructure()) + " at " + start.getChunkPos().x() + ", "
				+ start.getChunkPos().z();
	}

	private static boolean covered(List<BoundingBox> boxes, int x, int z) {
		for (BoundingBox box : boxes) {
			if (x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ()) return true;
		}
		return false;
	}

	private static long column(int x, int z) {
		return (long) x << 32 | z & 0xFFFFFFFFL;
	}

	/** One above the real ground's top: the highest block the far terrain shows that isn't a tree's or snow. */
	private static int realGround(LevelChunk chunk, BlockPos.MutableBlockPos pos, int bx, int bz, int minY) {
		for (int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, bx & 15, bz & 15); y >= minY; y--) {
			BlockState state = chunk.getBlockState(pos.set(bx, y, bz));
			if (FarTrees.shows(state) && !tree(state) && !state.is(Blocks.SNOW)) return y + 1;
		}
		return minY;
	}

	/** One above the highest block the far terrain shows (not trees) in a real column, at least the ground. */
	private static int realBuildingTop(LevelChunk chunk, BlockPos.MutableBlockPos pos, int bx, int bz, int ground) {
		for (int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, bx & 15, bz & 15); y >= ground; y--) {
			BlockState state = chunk.getBlockState(pos.set(bx, y, bz));
			if (FarTrees.shows(state) && !tree(state)) return y + 1;
		}
		return ground;
	}

	/** The same in a made column. */
	private static int madeBuildingTop(FarLand.Sheet sheet, int c, int minY, int ground) {
		int top = ground;
		for (long run : sheet.columns[c]) {
			int id = (int) (run >>> 16) >>> 8;
			if (id == FarLand.AIR || id == FarLand.HIDDEN) continue;
			BlockState state = sheet.palette.get(id);
			if (state != null && FarTrees.shows(state) && !tree(state)) top = Math.max(top, minY + (int) (run & 0xFFFF));
		}
		return top;
	}

	/** One above the highest leaves or log over the ground in a real column, 0 if none. */
	private static int realTreeTop(LevelChunk chunk, BlockPos.MutableBlockPos pos, int bx, int bz, int ground) {
		for (int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, bx & 15, bz & 15); y >= ground; y--) {
			if (tree(chunk.getBlockState(pos.set(bx, y, bz)))) return y + 1;
		}
		return 0;
	}

	/** The same in a made column. */
	private static int madeTreeTop(FarLand.Sheet sheet, int c, int minY) {
		int top = 0;
		for (long run : sheet.columns[c]) {
			int id = (int) (run >>> 16) >>> 8;
			if (id == FarLand.AIR || id == FarLand.HIDDEN) continue;
			BlockState state = sheet.palette.get(id);
			if (state != null && tree(state)) top = minY + (int) (run & 0xFFFF);
		}
		return top;
	}

	/** The block at voxel {@code v} of a column, or null if it's air or hidden. */
	private static BlockState state(FarLand.Sheet sheet, int c, int v) {
		for (long run : sheet.columns[c]) {
			if (v < (int) (run & 0xFFFF)) {
				int id = (int) (run >>> 16) >>> 8;
				return id == FarLand.AIR || id == FarLand.HIDDEN ? null : sheet.palette.get(id);
			}
		}
		return null;
	}

	/** Whether voxel {@code v} of a column is air or water. */
	private static boolean open(FarLand.Sheet sheet, int c, int v) {
		for (long run : sheet.columns[c]) {
			if (v < (int) (run & 0xFFFF)) {
				int id = (int) (run >>> 16) >>> 8;
				if (id == FarLand.AIR) return true;
				if (id == FarLand.HIDDEN) return false;
				BlockState state = sheet.palette.get(id);
				return state == null || state.isAir() || state.getBlock() instanceof LiquidBlock;
			}
		}
		return true;
	}

	private static String name(BlockState state) {
		return state.getBlock().builtInRegistryHolder().key().identifier().getPath();
	}

	/** Lowest, 1st percentile, median, 99th, highest of a histogram centered on 100. */
	private static String spread(int[] histogram, long total) {
		if (total == 0) return "-";
		int lowest = -1, highest = 0, p1 = 0, p50 = 0, p99 = 0;
		long seen = 0;
		for (int i = 0; i < histogram.length; i++) {
			if (histogram[i] == 0) continue;
			if (lowest < 0) lowest = i;
			highest = i;
			long before = seen;
			seen += histogram[i];
			if (before < total / 100 && seen >= total / 100) p1 = i;
			if (before < total / 2 && seen >= total / 2) p50 = i;
			if (before < total * 99 / 100 && seen >= total * 99 / 100) p99 = i;
		}
		return "lowest " + (lowest - 100) + ", 1% " + (p1 - 100) + ", median " + (p50 - 100) + ", 99% " + (p99 - 100) + ", highest " + (highest - 100);
	}

	private static String pct(long part, long whole) {
		return whole == 0 ? "-" : Reports.f1(100.0 * part / whole) + "%";
	}
}
