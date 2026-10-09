package com.afterburner.bench;

import com.afterburner.Afterburner;
import com.afterburner.Features;
import com.afterburner.tick.TickParking;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /afterburner-tick [seconds]}: measures server tick time (MSPT) and TPS. Op only. Meant for dedicated
 * servers; in singleplayer {@code /afterburner bench} measures ticks and FPS together.
 */
public final class TickCommand {
	private TickCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context,
			Commands.CommandSelection selection) {
		dispatcher.register(Commands.literal("afterburner-tick")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(c -> run(c.getSource(), 30))
				.then(Commands.argument("seconds", IntegerArgumentType.integer(5, 600))
						.executes(c -> run(c.getSource(), IntegerArgumentType.getInteger(c, "seconds")))));
	}

	private static int run(CommandSourceStack source, int seconds) {
		if (TickRecorder.recording()) {
			source.sendFailure(Component.literal("A tick benchmark is already running."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Measuring tick time for " + seconds + " s...").withStyle(ChatFormatting.GRAY), true);
		MinecraftServer server = source.getServer();
		TickRecorder.start(seconds, s -> report(source, server, s));
		return 1;
	}

	private static void report(CommandSourceStack source, MinecraftServer server, TickRecorder.Session session) {
		long[] ticks = session.ticks();
		Stats t = Stats.of(ticks);
		double seconds = session.seconds();
		List<String> lines = new ArrayList<>(tickLines(t, session.tps()));
		lines.add(worldLine(server));
		if (Features.PARKED_TICKS.enabled()) lines.add(TickParking.summary());
		lines.add(Reports.modsLine());
		String saved = Reports.write("tick", lines);

		source.sendSuccess(() -> Component.literal("Tick benchmark, " + Math.round(seconds) + " s").withStyle(ChatFormatting.GOLD), true);
		for (String line : lines) {
			source.sendSuccess(() -> Component.literal(line), true);
		}
		if (saved != null) {
			source.sendSuccess(() -> Component.literal("Saved to " + saved).withStyle(ChatFormatting.GRAY), true);
		}
		Afterburner.LOGGER.info("Tick benchmark: {}", String.join(" | ", lines));
	}

	/** The tick time lines, shared with the client's benchmark. */
	public static List<String> tickLines(Stats t, double tps) {
		return List.of(
				"TPS " + Reports.f1(tps) + " (" + t.count() + " ticks)",
				"Tick time avg " + Reports.f2(t.avgMs()) + " ms | 95% " + Reports.f2(t.p95Ms()) + " | 99% "
						+ Reports.f2(t.p99Ms()) + " | max " + Reports.f2(t.maxMs()) + " | ticks over 50 ms: " + t.over50Ms());
	}

	public static String worldLine(MinecraftServer server) {
		int entities = 0, chunks = 0;
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity ignored : level.getAllEntities()) entities++;
			chunks += level.getChunkSource().getLoadedChunksCount();
		}
		return "World: " + server.getPlayerCount() + " players, " + Reports.f0(entities) + " entities, "
				+ Reports.f0(chunks) + " loaded chunks";
	}
}
