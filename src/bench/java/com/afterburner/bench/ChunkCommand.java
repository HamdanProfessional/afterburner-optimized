package com.afterburner.bench;

import com.afterburner.Afterburner;
import com.afterburner.worldgen.ClimateIndex;
import com.afterburner.worldgen.FreeSpace;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code /afterburner-chunks <radius> [x z]}: loads every chunk in a square (radius 8 = 17x17 chunks) as fast as the
 * server can and times it. Chunks that were never generated get generated; chunks already saved get loaded from disk.
 * Without x z it picks a random spot far away, so it measures generating new terrain. Op only.
 */
public final class ChunkCommand {
	/** Holds the chunks loaded while measuring. It doesn't save, so a crash mid-benchmark can't leave chunks loaded. */
	public static final TicketType<ChunkPos> TICKET = TicketType.create("afterburner_benchmark", Comparator.comparingLong(ChunkPos::toLong));
	private static boolean running;

	private ChunkCommand() {
	}

	public static void init() {
	}

	/**
	 * Keeps the chunk loaded (and generated, if it wasn't) until {@link #release}; done when it's loaded. Asked for from
	 * another thread, as on the server thread the game waits for each chunk before going on to the next.
	 */
	public static CompletableFuture<?> load(ServerChunkCache chunks, ChunkPos pos) {
		chunks.addRegionTicket(TICKET, pos, 0, pos);
		return CompletableFuture.supplyAsync(() -> chunks.getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true), Util.backgroundExecutor())
				.thenCompose(future -> future);
	}

	public static void release(ServerChunkCache chunks, ChunkPos pos) {
		chunks.removeRegionTicket(TICKET, pos, 0, pos);
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context,
			Commands.CommandSelection selection) {
		dispatcher.register(Commands.literal("afterburner-chunks")
				.requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
						.executes(c -> {
							ThreadLocalRandom r = ThreadLocalRandom.current();
							int x = (r.nextBoolean() ? 1 : -1) * r.nextInt(20_000, 40_000);
							int z = (r.nextBoolean() ? 1 : -1) * r.nextInt(20_000, 40_000);
							return run(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), x, z);
						})
						.then(Commands.argument("x", IntegerArgumentType.integer())
								.then(Commands.argument("z", IntegerArgumentType.integer())
										.executes(c -> run(c.getSource(), IntegerArgumentType.getInteger(c, "radius"),
												IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z")))))));
	}

	private static int run(CommandSourceStack source, int radius, int cx, int cz) {
		if (running) {
			source.sendFailure(Component.literal("A chunk benchmark is already running."));
			return 0;
		}
		running = true;
		ServerLevel level = source.getLevel();
		MinecraftServer server = source.getServer();
		ServerChunkCache chunks = level.getChunkSource();
		int side = radius * 2 + 1;
		source.sendSuccess(() -> Component.literal("Loading " + side * side + " chunks around chunk " + cx + ", " + cz + "...")
				.withStyle(ChatFormatting.GRAY), true);

		TickRecorder.Session ticks = TickRecorder.start();
		long start = System.nanoTime();
		long lightNanos = LightStats.nanos(), lightBatches = LightStats.batches();
		List<ChunkPos> positions = new ArrayList<>();
		List<CompletableFuture<?>> futures = new ArrayList<>();
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				ChunkPos pos = new ChunkPos(cx + dx, cz + dz);
				positions.add(pos);
				futures.add(load(chunks, pos));
			}
		}

		CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenCompleteAsync((ignored, error) -> {
			double seconds = (System.nanoTime() - start) / 1e9;
			long[] tickTimes = ticks.stop();
			for (ChunkPos pos : positions) release(chunks, pos);
			running = false;
			if (error != null) {
				source.sendFailure(Component.literal("Chunk benchmark failed: " + error));
				Afterburner.LOGGER.error("Chunk benchmark failed", error);
				return;
			}
			Stats t = Stats.of(tickTimes);
			double lightSeconds = (LightStats.nanos() - lightNanos) / 1e9;
			long batches = LightStats.batches() - lightBatches;
			List<String> lines = new ArrayList<>(List.of(
					"Chunks: " + Reports.f0(positions.size()) + " in " + Reports.f2(seconds) + " s = "
							+ Reports.f1(positions.size() / seconds) + " chunks/s (around chunk " + cx + ", " + cz + ")",
					"Main thread while loading: TPS " + Reports.f1(ticks.tps()) + ", tick time avg " + Reports.f2(t.avgMs())
							+ " ms, max " + Reports.f2(t.maxMs()) + " ms",
					"Light thread busy " + Reports.f2(lightSeconds) + " s (" + Reports.f0(100 * lightSeconds / seconds)
							+ "% of the time), " + Reports.f0(batches) + " batches",
					"Worker threads: " + (Runtime.getRuntime().availableProcessors() - 1) + ", " + Reports.modsLine()));
			if (FreeSpace.CHECK) lines.add(FreeSpace.summary());
			if (ClimateIndex.CHECK) lines.add(ClimateIndex.summary());
			String saved = Reports.write("chunks", lines);
			source.sendSuccess(() -> Component.literal("Chunk benchmark done").withStyle(ChatFormatting.GOLD), true);
			for (String line : lines) source.sendSuccess(() -> Component.literal(line), true);
			if (saved != null) source.sendSuccess(() -> Component.literal("Saved to " + saved).withStyle(ChatFormatting.GRAY), true);
			Afterburner.LOGGER.info("Chunk benchmark: {}", String.join(" | ", lines));
		}, server);
		return 1;
	}
}
