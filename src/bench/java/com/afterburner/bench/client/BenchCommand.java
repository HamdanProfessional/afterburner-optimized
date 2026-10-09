package com.afterburner.bench.client;

import com.afterburner.Afterburner;
import com.afterburner.bench.Reports;
import com.afterburner.bench.Stats;
import com.afterburner.bench.TickCommand;
import com.afterburner.bench.TickRecorder;
import com.afterburner.client.Addons;
import com.afterburner.client.perf.FpsTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /afterburner bench [seconds]}: measures FPS and frame times while you play. In singleplayer it also measures
 * the world's tick time, since that server runs inside the game.
 */
public final class BenchCommand {
	/** Vanilla's FPS slider shows "Unlimited" at this value. */
	private static final int UNLIMITED_FPS = 260;

	private BenchCommand() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext context) {
		dispatcher.register(ClientCommands.literal("afterburner")
				.then(ClientCommands.literal("bench")
						.executes(c -> start(c.getSource(), 30))
						.then(ClientCommands.argument("seconds", IntegerArgumentType.integer(5, 600))
								.executes(c -> start(c.getSource(), IntegerArgumentType.getInteger(c, "seconds"))))));
	}

	private static int start(FabricClientCommandSource source, int seconds) {
		if (FrameRecorder.running()) {
			source.sendError(Component.literal("A benchmark is already running."));
			return 0;
		}
		Minecraft mc = source.getClient();
		TickRecorder.Session ticks = mc.getSingleplayerServer() != null ? TickRecorder.start() : null;
		source.sendFeedback(Component.literal("Benchmarking for " + seconds + " s. Play normally, menus and chat pause it.")
				.withStyle(ChatFormatting.GRAY));
		FrameWaits.reset();
		FrameWaits.measuring = true;
		FrameRecorder.start(seconds, run -> {
			FrameWaits.measuring = false;
			long[] tickTimes = ticks != null ? ticks.stop() : null;
			if (run != null) report(mc, run, tickTimes, ticks != null ? ticks.tps() : 0);
		});
		return 1;
	}

	private static void report(Minecraft mc, FrameRecorder.Run run, long[] ticks, double tps) {
		Stats f = Stats.of(run.frames());
		List<String> lines = new ArrayList<>();
		lines.add("FPS avg " + Reports.f0(f.count() / (f.totalMs() / 1000)) + " | 1% low " + Reports.f0(Stats.fps(f.p99Ms()))
				+ " | 0.1% low " + Reports.f0(Stats.fps(f.p999Ms())) + " (" + Reports.f0(f.count()) + " frames)");
		lines.add("Frame time avg " + Reports.f2(f.avgMs()) + " ms | 99% " + Reports.f2(f.p99Ms()) + " | max "
				+ Reports.f2(f.maxMs()) + " | stutters over 50 ms: " + f.over50Ms());
		if (ticks != null) {
			lines.addAll(TickCommand.tickLines(Stats.of(ticks), tps));
		} else {
			lines.add("Tick time: not measured (only works in singleplayer, use /afterburner-tick on a server)");
		}
		lines.add(settingsLine(mc));
		lines.add("Target FPS: " + (FpsTarget.target() == 0 ? "off" : FpsTarget.target() + " (at the end: " + FpsTarget.describe() + ")"));
		lines.add("World: " + (mc.level != null ? Reports.f0(mc.level.getEntityCount()) : "?") + " entities nearby");
		lines.add(Reports.modsLine());
		lines.add(bottleneckLine(mc));

		List<String> notes = new ArrayList<>();
		Options o = mc.options;
		if (o.enableVsync().get() || o.framerateLimit().get() < UNLIMITED_FPS) {
			notes.add("Note: VSync or an FPS limit is on, so FPS is capped. Turn both off to measure real performance.");
		}
		if (run.menuOpened() > 0) {
			notes.add("Note: menus were opened " + run.menuOpened() + " times. That time wasn't counted for FPS, but pausing stops ticks, so TPS may read low.");
		}

		List<String> file = new ArrayList<>(lines);
		file.addAll(FrameWaits.lines());
		file.addAll(notes);
		file.add("");
		file.add("1% low = the FPS at the slowest 1% of frames (99th percentile frame time). 0.1% low is the same for 0.1%.");
		file.add("Tick time = how long the server spends on one tick. Under 50 ms keeps 20 TPS.");
		String saved = Reports.write("bench", file);

		if (mc.player == null) return;
		mc.player.sendSystemMessage(Component.literal("Benchmark done").withStyle(ChatFormatting.GOLD));
		for (String line : lines) mc.player.sendSystemMessage(Component.literal(line));
		for (String note : notes) mc.player.sendSystemMessage(Component.literal(note).withStyle(ChatFormatting.YELLOW));
		if (saved != null) mc.player.sendSystemMessage(Component.literal("Saved to " + saved).withStyle(ChatFormatting.GRAY));
		Afterburner.LOGGER.info("Benchmark: {}", String.join(" | ", lines));
	}

	/** Whether frames wait for the graphics card or for the processor, which says what's worth lowering. */
	private static String bottleneckLine(Minecraft mc) {
		Options o = mc.options;
		double gpu = FrameWaits.gpuShare();
		String share = Reports.f0(gpu * 100) + "% of each frame waiting for the graphics card";
		if (o.enableVsync().get() || o.framerateLimit().get() < UNLIMITED_FPS) return "Limit: can't tell with VSync or an FPS limit on (" + share + ")";
		if (gpu > 0.3) return "Limit: mostly the graphics card (" + share + "). Render distance, far terrain distance and shader packs cost the most.";
		if (gpu < 0.1) return "Limit: mostly the processor (" + share + "). Render distance, simulation distance and many mobs cost the most.";
		return "Limit: both about evenly (" + share + ")";
	}

	private static String settingsLine(Minecraft mc) {
		Options o = mc.options;
		Window w = mc.getWindow();
		int limit = o.framerateLimit().get();
		return "Settings: " + w.getWidth() + "x" + w.getHeight() + ", render distance " + o.getEffectiveRenderDistance()
				+ ", simulation distance " + o.simulationDistance().get() + ", VSync " + (o.enableVsync().get() ? "on" : "off")
				+ ", FPS limit " + (limit >= UNLIMITED_FPS ? "unlimited" : limit)
				+ ", far terrain " + (Addons.farTerrainChunks() > 0 ? Addons.farTerrainChunks() + " chunks" : "off");
	}
}
