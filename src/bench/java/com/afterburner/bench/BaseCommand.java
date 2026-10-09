package com.afterburner.bench;

import com.afterburner.Afterburner;
import com.afterburner.Features;
import com.afterburner.ai.AiCheck;
import com.afterburner.collision.CollisionCheck;
import com.afterburner.entity.HopperSearch;
import com.afterburner.entity.SectionSearch;
import com.afterburner.spawn.SpawnLookups;
import com.afterburner.tick.TickingSections;
import com.afterburner.tick.TickParking;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@code /afterburner-base [seconds]}: builds a busy survival base on a platform high above spawn and measures the
 * server's tick time while it runs. Op only, and it builds over whatever is there, so use a test world.
 * <ul>
 * <li>a pen of villagers with job blocks and a bell, a cow pen with a fence and a wall, and chickens standing on a floor of
 * hoppers, with a few boats and minecarts in the pens;</li>
 * <li>a closed box of zombies and skeletons, like a mob farm's collection area;</li>
 * <li>chains of hoppers moving cobblestone between chests, and furnaces smelting;</li>
 * <li>observer clocks pulsing long lines of redstone dust over redstone lamps.</li>
 * </ul>
 * If nobody is on the server, a stand-in player joins and stands in the middle, so the server also spawns mobs, ticks
 * blocks and sends updates as it would for a real player. After a warm-up the ticks are timed for {@code seconds}.
 */
public final class BaseCommand {
	/** The floor everything stands on is at Y - 1. */
	private static final int Y = 150;
	private static final int WARMUP_SECONDS = 20;
	private static final int FLAGS = Block.UPDATE_ALL;
	private static final Block[] JOB_BLOCKS = {Blocks.COMPOSTER, Blocks.LECTERN, Blocks.BARREL, Blocks.SMOKER, Blocks.BLAST_FURNACE,
			Blocks.CARTOGRAPHY_TABLE, Blocks.FLETCHING_TABLE, Blocks.LOOM, Blocks.SMITHING_TABLE, Blocks.STONECUTTER,
			Blocks.BREWING_STAND, Blocks.CAULDRON};
	private static boolean running;

	private BaseCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context,
			Commands.CommandSelection selection) {
		dispatcher.register(Commands.literal("afterburner-base")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(c -> run(c.getSource(), 60))
				.then(Commands.argument("seconds", IntegerArgumentType.integer(10, 600))
						.executes(c -> run(c.getSource(), IntegerArgumentType.getInteger(c, "seconds")))));
	}

	private static int run(CommandSourceStack source, int seconds) {
		if (running || TickRecorder.recording()) {
			source.sendFailure(Component.literal("A benchmark is already running."));
			return 0;
		}
		running = true;
		MinecraftServer server = source.getServer();
		ServerLevel level = server.overworld();
		CommandSourceStack quiet = source.withSuppressedOutput();
		for (String command : List.of("time set noon", "weather clear", "gamerule advance_time false", "gamerule advance_weather false")) {
			server.getCommands().performPrefixedCommand(quiet, command);
		}
		source.sendSuccess(() -> Component.literal("Building the test base...").withStyle(ChatFormatting.GRAY), true);

		List<CompletableFuture<?>> loads = new ArrayList<>();
		for (int cz = -3; cz <= 2; cz++) {
			for (int cx = -3; cx <= 2; cx++) {
				loads.add(level.getChunkSource().addTicketAndLoadWithRadius(ChunkCommand.TICKET, new ChunkPos(cx, cz), 0));
			}
		}
		CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).thenRunAsync(() -> {
			if (!server.isRunning()) throw new IllegalStateException("the server is stopping");
			build(level);
			if (server.getPlayerCount() == 0) BenchPlayer.join(server, level, new Vec3(0.5, Y, 0.5));
			spawnMobs(level);
			source.sendSuccess(() -> Component.literal("Built. Warming up for " + WARMUP_SECONDS + " s, then measuring for "
					+ seconds + " s...").withStyle(ChatFormatting.GRAY), true);
		}, server).thenRunAsync(() -> TickRecorder.start(seconds, session -> report(source, server, session)),
				CompletableFuture.delayedExecutor(WARMUP_SECONDS, TimeUnit.SECONDS, server)).exceptionally(e -> {
			running = false;
			Afterburner.LOGGER.error("Base benchmark failed", e);
			source.sendFailure(Component.literal("Base benchmark failed: " + e));
			return null;
		});
		return 1;
	}

	private static void report(CommandSourceStack source, MinecraftServer server, TickRecorder.Session session) {
		running = false;
		List<String> lines = new ArrayList<>(TickCommand.tickLines(Stats.of(session.ticks()), session.tps()));
		lines.add(TickCommand.worldLine(server));
		lines.add(entitiesLine(server.overworld()));
		if (CollisionCheck.ENABLED) lines.add(CollisionCheck.summary());
		if (SectionSearch.CHECK) lines.add(SectionSearch.summary());
		if (TickingSections.CHECK) lines.add(TickingSections.summary());
		if (HopperSearch.CHECK) lines.add(HopperSearch.summary());
		if (SpawnLookups.CHECK) lines.add(SpawnLookups.summary());
		if (AiCheck.ENABLED) lines.add(AiCheck.summary());
		if (Features.PARKED_TICKS.enabled()) lines.add(TickParking.summary());
		lines.add(Reports.modsLine());
		String saved = Reports.write("base", lines);
		source.sendSuccess(() -> Component.literal("Base benchmark, " + Math.round(session.seconds()) + " s").withStyle(ChatFormatting.GOLD), true);
		for (String line : lines) source.sendSuccess(() -> Component.literal(line), true);
		if (saved != null) source.sendSuccess(() -> Component.literal("Saved to " + saved).withStyle(ChatFormatting.GRAY), true);
		Afterburner.LOGGER.info("Base benchmark: {}", String.join(" | ", lines));
	}

	/** The most common entities, e.g. "cow 150, zombie 98, ...". */
	private static String entitiesLine(ServerLevel level) {
		Map<String, Integer> counts = new TreeMap<>();
		for (Entity entity : level.getAllEntities()) {
			counts.merge(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath(), 1, Integer::sum);
		}
		List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
		sorted.sort((a, b) -> b.getValue() - a.getValue());
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, Integer> e : sorted.subList(0, Math.min(8, sorted.size()))) parts.add(e.getKey() + " " + e.getValue());
		return "Entities: " + String.join(", ", parts);
	}

	private static void build(ServerLevel level) {
		BlockState stone = Blocks.STONE.defaultBlockState(), glass = Blocks.GLASS.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
		fill(level, -42, Y - 1, -42, 42, Y - 1, 42, stone);
		fill(level, -42, Y, -42, 42, Y + 4, 42, air);

		// The player stands in a glass cell in the middle.
		fill(level, -1, Y, -1, 1, Y + 2, 1, glass);
		fill(level, 0, Y, 0, 0, Y + 1, 0, air);

		// Villagers (north-west) with job blocks every 5 blocks and a bell.
		pen(level, -38, -38, -12, -12);
		for (int i = 0; i < 25; i++) {
			set(level, -35 + i % 5 * 5, Y, -35 + i / 5 * 5, JOB_BLOCKS[i % JOB_BLOCKS.length].defaultBlockState());
		}
		set(level, -24, Y, -24, Blocks.BELL.defaultBlockState());

		// Cows (north-east), and chickens on a floor of hoppers dropping into chests.
		pen(level, 12, -38, 38, -22);
		// A fence and a wall across part of it: blocks taller than a block, which collisions treat differently.
		fill(level, 14, Y, -30, 24, Y, -30, Blocks.OAK_FENCE.defaultBlockState());
		fill(level, 26, Y, -26, 36, Y, -26, Blocks.COBBLESTONE_WALL.defaultBlockState());
		pen(level, 12, -16, 26, -2);
		for (int x = 13; x <= 25; x++) {
			for (int z = -15; z <= -3; z++) {
				set(level, x, Y - 2, z, Blocks.CHEST.defaultBlockState());
				set(level, x, Y - 1, z, Blocks.HOPPER.defaultBlockState());
			}
		}

		// Furnaces smelting iron, between the pens.
		for (int x = -10; x <= 9; x++) {
			for (int z = -9; z <= -8; z++) {
				BlockPos pos = new BlockPos(x, Y, z);
				level.setBlock(pos, Blocks.FURNACE.defaultBlockState(), FLAGS);
				if (level.getBlockEntity(pos) instanceof Container furnace) {
					furnace.setItem(0, new ItemStack(Items.RAW_IRON, 16));
					furnace.setItem(1, new ItemStack(Items.COAL, 2));
				}
			}
		}

		// Mob farm collection box (south-west).
		pen(level, -38, 12, -12, 38);

		// Hopper chains moving cobblestone from chest to chest (south-east).
		BlockState hopperEast = Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST);
		for (int z = 4; z <= 16; z += 3) {
			BlockPos from = new BlockPos(4, Y, z);
			level.setBlock(from, Blocks.CHEST.defaultBlockState(), FLAGS);
			if (level.getBlockEntity(from) instanceof Container chest) {
				for (int slot = 0; slot < chest.getContainerSize(); slot++) chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
			}
			for (int x = 5; x <= 36; x++) set(level, x, Y, z, hopperEast);
			set(level, 37, Y, z, Blocks.CHEST.defaultBlockState());
		}

		// Observer clocks driving lines of dust on top of redstone lamps, with a repeater halfway (south-east).
		BlockState dust = Blocks.REDSTONE_WIRE.defaultBlockState(), lamp = Blocks.REDSTONE_LAMP.defaultBlockState();
		for (int z = 22; z <= 37; z += 3) {
			for (int x = 6; x <= 36; x++) {
				if (x == 21) {
					set(level, x, Y, z, Blocks.REPEATER.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST));
				} else {
					set(level, x, Y - 1, z, lamp);
					set(level, x, Y, z, dust);
				}
			}
			// Each observer watches the other, so placing the second one starts the clock.
			set(level, 4, Y, z, Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.EAST));
			set(level, 5, Y, z, Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.WEST));
		}
	}

	private static void spawnMobs(ServerLevel level) {
		Random random = new Random(20261006L);
		spawn(level, random, EntityTypes.VILLAGER, 60, -37, -37, -13, -13);
		spawn(level, random, EntityTypes.COW, 150, 13, -37, 37, -23);
		spawn(level, random, EntityTypes.CHICKEN, 80, 13, -15, 25, -3);
		spawn(level, random, EntityTypes.ZOMBIE, 100, -37, 13, -13, 37);
		spawn(level, random, EntityTypes.SKELETON, 40, -37, 13, -13, 37);
		// A few things that can be bumped into.
		spawn(level, random, EntityTypes.OAK_BOAT, 8, 13, -37, 37, -23);
		spawn(level, random, EntityTypes.MINECART, 8, -37, -37, -13, -13);
	}

	private static void spawn(ServerLevel level, Random random, EntityType<?> type, int count, int x0, int z0, int x1, int z1) {
		for (int i = 0; i < count; i++) {
			BlockPos pos;
			do {
				pos = new BlockPos(random.nextInt(x0, x1 + 1), Y, random.nextInt(z0, z1 + 1));
			} while (!level.getBlockState(pos).isAir());
			if (type.spawn(level, pos, EntitySpawnReason.COMMAND) instanceof Mob mob) mob.setPersistenceRequired();
		}
	}

	/** Glass walls three high around the edge, and a stone roof (so zombies and skeletons don't burn). */
	private static void pen(ServerLevel level, int x0, int z0, int x1, int z1) {
		BlockState glass = Blocks.GLASS.defaultBlockState();
		for (int y = Y; y < Y + 3; y++) {
			fill(level, x0, y, z0, x1, y, z0, glass);
			fill(level, x0, y, z1, x1, y, z1, glass);
			fill(level, x0, y, z0, x0, y, z1, glass);
			fill(level, x1, y, z0, x1, y, z1, glass);
		}
		fill(level, x0, Y + 3, z0, x1, Y + 3, z1, Blocks.STONE.defaultBlockState());
	}

	private static void fill(ServerLevel level, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
		for (int y = y0; y <= y1; y++) {
			for (int z = z0; z <= z1; z++) {
				for (int x = x0; x <= x1; x++) set(level, x, y, z, state);
			}
		}
	}

	private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
		level.setBlock(new BlockPos(x, y, z), state, FLAGS);
	}
}
