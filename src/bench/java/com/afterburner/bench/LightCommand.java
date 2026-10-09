package com.afterburner.bench;

import com.afterburner.Afterburner;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * {@code /afterburner-light [radius] [x z]}: loads a square of chunks (default radius 16 around chunk 0, 0), then makes
 * changes that keep the light engine busy and times how long it takes to catch up after each one:
 * <ol>
 * <li>3000 glowstone blocks just above the ground, then the same blocks removed again;</li>
 * <li>30 craters dug into the ground, letting sky light in;</li>
 * <li>a 96x96 stone roof high above the ground, then the roof removed again.</li>
 * </ol>
 * The changes are the same every time for the same world, and the light values afterwards are summed into a checksum,
 * so runs with and without an optimization can be checked for giving the same light. Op only.
 */
public final class LightCommand {
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
	private static boolean running;

	private LightCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context,
			Commands.CommandSelection selection) {
		dispatcher.register(Commands.literal("afterburner-light")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(c -> run(c.getSource(), 16, 0, 0))
				.then(Commands.argument("radius", IntegerArgumentType.integer(2, 32))
						.executes(c -> run(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), 0, 0))
						.then(Commands.argument("x", IntegerArgumentType.integer())
								.then(Commands.argument("z", IntegerArgumentType.integer())
										.executes(c -> run(c.getSource(), IntegerArgumentType.getInteger(c, "radius"),
												IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z")))))));
	}

	private record Step(String name, Consumer<List<BlockPos>> edit) {
	}

	private record Result(String name, int changed, double wallMs, double lightMs, long steps, long checksum) {
	}

	private static int run(CommandSourceStack source, int radius, int cx, int cz) {
		if (running) {
			source.sendFailure(Component.literal("A light benchmark is already running."));
			return 0;
		}
		running = true;
		ServerLevel level = source.getLevel();
		MinecraftServer server = source.getServer();
		ServerChunkCache chunks = level.getChunkSource();
		server.getCommands().performPrefixedCommand(source.withSuppressedOutput(), "gamerule random_tick_speed 0");
		source.sendSuccess(() -> Component.literal("Loading chunks for the light benchmark...").withStyle(ChatFormatting.GRAY), true);

		List<ChunkPos> area = new ArrayList<>();
		List<CompletableFuture<?>> loads = new ArrayList<>();
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				ChunkPos pos = new ChunkPos(cx + dx, cz + dz);
				area.add(pos);
				loads.add(chunks.addTicketAndLoadWithRadius(ChunkCommand.TICKET, pos, 0));
			}
		}

		// Blocks are changed in the middle of the area, so every chunk around a change is loaded and lit.
		int minX = (cx - radius + 2) * 16, maxX = (cx + radius - 1) * 16 - 1;
		int minZ = (cz - radius + 2) * 16, maxZ = (cz + radius - 1) * 16 - 1;
		int midX = (minX + maxX) / 2, midZ = (minZ + maxZ) / 2;
		Random random = new Random(20261006L);
		List<BlockPos> glowstone = new ArrayList<>();
		int[] roof = new int[1];
		List<Step> steps = List.of(
				new Step("glowstone placed", changed -> {
					for (int i = 0; i < 3000; i++) {
						int x = random.nextInt(minX, maxX + 1), z = random.nextInt(minZ, maxZ + 1);
						BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + random.nextInt(0, 6), z);
						if (set(level, pos, Blocks.GLOWSTONE.defaultBlockState())) {
							changed.add(pos);
							glowstone.add(pos);
						}
					}
				}),
				new Step("glowstone removed", changed -> {
					for (BlockPos pos : glowstone) {
						if (set(level, pos, Blocks.AIR.defaultBlockState())) changed.add(pos);
					}
				}),
				new Step("craters dug", changed -> {
					for (int i = 0; i < 30; i++) {
						int x = random.nextInt(minX + 12, maxX - 11), z = random.nextInt(minZ + 12, maxZ - 11);
						int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 4;
						int r = random.nextInt(6, 12);
						for (int dy = -r; dy <= r; dy++) {
							for (int dz = -r; dz <= r; dz++) {
								for (int dx = -r; dx <= r; dx++) {
									if (dx * dx + dy * dy + dz * dz > r * r) continue;
									BlockPos pos = new BlockPos(x + dx, y + dy, z + dz);
									if (!level.getBlockState(pos).is(Blocks.BEDROCK) && set(level, pos, Blocks.AIR.defaultBlockState())) {
										changed.add(pos);
									}
								}
							}
						}
					}
				}),
				new Step("roof built", changed -> {
					roof[0] = roofY(level, midX, midZ);
					roof(level, midX, roof[0], midZ, Blocks.STONE.defaultBlockState(), changed);
				}),
				new Step("roof removed", changed -> roof(level, midX, roof[0], midZ, Blocks.AIR.defaultBlockState(), changed)));

		long start = System.nanoTime();
		CompletableFuture<List<Result>> results = CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new))
				.thenComposeAsync(ignored -> lightDone(level, area), server)
				.thenApplyAsync(ignored -> new ArrayList<Result>(), server);
		for (Step step : steps) {
			results = results.thenComposeAsync(list -> {
				List<BlockPos> changed = new ArrayList<>();
				long lightBefore = LightStats.nanos(), stepsBefore = LightStats.steps();
				long t0 = System.nanoTime();
				step.edit.accept(changed);
				return lightDone(level, area).thenApplyAsync(ignored -> {
					double wall = (System.nanoTime() - t0) / 1e6;
					double light = (LightStats.nanos() - lightBefore) / 1e6;
					list.add(new Result(step.name, changed.size(), wall, light, LightStats.steps() - stepsBefore, checksum(level, minX, maxX, minZ, maxZ)));
					return list;
				}, server);
			}, server);
		}
		results.whenCompleteAsync((list, error) -> {
			for (ChunkPos pos : area) chunks.removeTicketWithRadius(ChunkCommand.TICKET, pos, 0);
			running = false;
			if (error != null) {
				source.sendFailure(Component.literal("Light benchmark failed: " + error));
				Afterburner.LOGGER.error("Light benchmark failed", error);
				return;
			}
			double totalWall = 0, totalLight = 0;
			long sum = 0;
			List<String> lines = new ArrayList<>();
			for (Result r : list) {
				totalWall += r.wallMs;
				totalLight += r.lightMs;
				sum = sum * 31 + r.checksum;
				lines.add(r.name + ": " + Reports.f0(r.changed) + " blocks, light done in " + Reports.f0(r.wallMs) + " ms, light thread busy "
						+ Reports.f0(r.lightMs) + " ms (" + Reports.f0(r.steps) + " steps, " + Reports.f0(r.lightMs * 1e6 / Math.max(1, r.steps)) + " ns each), light checksum " + Long.toHexString(r.checksum));
			}
			lines.addFirst("Light total: light thread busy " + Reports.f0(totalLight) + " ms, done in " + Reports.f0(totalWall)
					+ " ms (" + Reports.f1((System.nanoTime() - start) / 1e9) + " s with loading), checksum " + Long.toHexString(sum)
					+ ", " + area.size() + " chunks loaded");
			lines.add(Reports.modsLine());
			String saved = Reports.write("light", lines);
			source.sendSuccess(() -> Component.literal("Light benchmark done").withStyle(ChatFormatting.GOLD), true);
			for (String line : lines) source.sendSuccess(() -> Component.literal(line), true);
			if (saved != null) source.sendSuccess(() -> Component.literal("Saved to " + saved).withStyle(ChatFormatting.GRAY), true);
			Afterburner.LOGGER.info("Light benchmark: {}", String.join(" | ", lines));
		}, server);
		return 1;
	}

	private static boolean set(ServerLevel level, BlockPos pos, BlockState state) {
		return level.getBlockState(pos) != state && level.setBlock(pos, state, FLAGS);
	}

	/** The height of a roof one block thick, 16 blocks above the highest ground under it. */
	private static int roofY(ServerLevel level, int midX, int midZ) {
		int top = level.getMinY();
		for (int z = midZ - 48; z < midZ + 48; z++) {
			for (int x = midX - 48; x < midX + 48; x++) top = Math.max(top, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
		}
		return Math.min(top + 16, level.getMaxY() - 1);
	}

	private static void roof(ServerLevel level, int midX, int y, int midZ, BlockState state, List<BlockPos> changed) {
		for (int z = midZ - 48; z < midZ + 48; z++) {
			for (int x = midX - 48; x < midX + 48; x++) {
				BlockPos pos = new BlockPos(x, y, z);
				if (set(level, pos, state)) changed.add(pos);
			}
		}
	}

	/**
	 * Completes once the light engine has finished everything queued for the area so far. Each chunk's light tasks run
	 * in order, so a task queued behind them runs after they're done; it's done twice in case the first round queued more.
	 */
	private static CompletableFuture<?> lightDone(ServerLevel level, List<ChunkPos> area) {
		ThreadedLevelLightEngine light = level.getChunkSource().getLightEngine();
		return waitAll(light, area).thenCompose(ignored -> waitAll(light, area));
	}

	private static CompletableFuture<?> waitAll(ThreadedLevelLightEngine light, List<ChunkPos> area) {
		List<CompletableFuture<?>> waits = new ArrayList<>(area.size());
		for (ChunkPos pos : area) waits.add(light.waitForPendingTasks(pos.x(), pos.z()));
		return CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new));
	}

	/**
	 * The light of every section in the area, mixed into one number. A section stored as "all the same value" counts the
	 * same as one stored block by block with that value everywhere.
	 */
	private static long checksum(ServerLevel level, int minX, int maxX, int minZ, int maxZ) {
		LevelLightEngine light = level.getLightEngine();
		long sum = 0;
		for (LightLayer layer : LightLayer.values()) {
			LayerLightEventListener listener = light.getLayerListener(layer);
			for (int sx = minX >> 4; sx <= maxX >> 4; sx++) {
				for (int sz = minZ >> 4; sz <= maxZ >> 4; sz++) {
					for (int sy = light.getMinLightSection(); sy < light.getMaxLightSection(); sy++) {
						DataLayer data = listener.getDataLayerData(SectionPos.of(sx, sy, sz));
						int hash = data == null ? 1 : data.isDefinitelyHomogenous() ? FILLED[data.get(0, 0, 0)] : Arrays.hashCode(data.getData());
						sum = sum * 1_000_003 + hash;
					}
				}
			}
		}
		return sum;
	}

	/** What {@link Arrays#hashCode(byte[])} gives for a section filled with each light level. */
	private static final int[] FILLED = new int[16];

	static {
		for (int v = 0; v < 16; v++) {
			byte[] bytes = new byte[DataLayer.SIZE];
			Arrays.fill(bytes, (byte) (v | v << 4));
			FILLED[v] = Arrays.hashCode(bytes);
		}
	}
}
