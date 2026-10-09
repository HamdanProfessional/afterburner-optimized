package com.afterburner.bench.client;

import com.afterburner.Afterburner;
import com.afterburner.Features;
import com.afterburner.bench.MemoryReport;
import com.afterburner.bench.Reports;
import com.afterburner.bench.Stats;
import com.afterburner.bench.TickCommand;
import com.afterburner.bench.TickRecorder;
import com.afterburner.client.gui.SettingsScreen;
import com.afterburner.client.render.AnimatedSprites;
import com.afterburner.client.render.BiomeMemo;
import com.afterburner.client.render.BlockEntitySections;
import com.afterburner.client.render.ChunkBatcher;
import com.afterburner.client.render.LeafCulling;
import com.afterburner.client.render.LightMemo;
import com.afterburner.client.render.OcclusionRays;
import com.afterburner.client.render.EntityCulling;
import com.afterburner.client.render.OcclusionCulling;
import com.afterburner.client.render.OffscreenBuilds;
import com.afterburner.client.render.SectionFaces;
import com.afterburner.client.render.TranslucentCulling;
import com.afterburner.tick.TickParking;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * A benchmark that runs by itself, for tools/clientbench.py. If {@code <game dir>/afterburner/autobench.properties}
 * exists when the game starts, then once a singleplayer world is open it:
 * <ol>
 * <li>sets the render distance, turns VSync and the FPS limit off, makes it noon with clear weather, and puts the
 * player in creative, flying 40 blocks above the ground;</li>
 * <li>turns the camera in full circles until every chunk in range is loaded and built ("warmup");</li>
 * <li>measures the frames of one more full circle, saves the results and a screenshot, and closes the game.</li>
 * </ol>
 * The file is deleted when it's read, so the next normal launch doesn't benchmark.
 */
public final class AutoBench {
	private static final Path DIR = FabricLoader.getInstance().getGameDir().resolve("afterburner");

	private static AutoBench instance;

	private final String label;
	private final int renderDistance;
	private final int seconds;
	private final boolean quit;
	/** If set, the game then sits in the pause menu this long before the results are saved (for the memory trim). */
	private final int pauseSeconds;
	/** How high above the ground the camera flies, and how far down it looks (degrees). */
	private final int altitude, pitch, offsetX, offsetZ;
	/** Stand in the middle of the nearest area of this biome (like "plains") instead of at the spawn point, or null. */
	private final @Nullable String biome;
	/** Blocks a second to fly straight south while measuring, over land that loads as it comes; 0: turn on the spot. */
	private final double fly;
	private double flyX, flyY, flyZ;
	/** A command run (as if typed, so client commands like spark's "sparkc" too) when measuring starts, or null. */
	private final @Nullable String command;
	/** Shows the debug screen (F3) while measuring and saves a screenshot of it halfway through. */
	private final boolean f3;
	private boolean f3Saved;

	private enum Phase { WAIT_WORLD, WARMUP, SETTLE, MEASURE, PAUSED, SCREENS, DONE }

	private Phase phase = Phase.WAIT_WORLD;
	private long joinedAt, spinOrigin, phaseStart, lastChange, lastLog;
	private int lastChunks = -1;
	private boolean wasBusy;
	private long readyAt;
	/** When the section builders last went from idle to busy, or 0 while idle. */
	private long busySince;
	private int circles, throttledFrames;
	/** With {@code -Dafterburner.benchStills=N}, N frames in a row are saved while the camera holds still: any big difference between them is flicker. */
	private static final int STILLS = Integer.getInteger("afterburner.benchStills", 0);
	private int stills;
	/** With {@code -Dafterburner.benchScreens=true}, Video Settings (Sodium's, if installed) and the Afterburner page are opened and saved as screenshots before quitting. */
	private static final boolean SCREENS = Boolean.getBoolean("afterburner.benchScreens");
	private int screensShown;
	private FrameRecorder.Run measured;
	/** The singleplayer server's ticks during the measured part. */
	private TickRecorder.Session serverTicks;
	private long[] serverTickTimes;
	private double serverTps;

	private AutoBench(Properties p) {
		label = p.getProperty("label", "run");
		renderDistance = Integer.parseInt(p.getProperty("renderDistance", "16"));
		seconds = Integer.parseInt(p.getProperty("seconds", "20"));
		quit = !"false".equals(p.getProperty("quit"));
		pauseSeconds = Integer.parseInt(p.getProperty("pauseSeconds", "0"));
		altitude = Integer.parseInt(p.getProperty("altitude", "40"));
		pitch = Integer.parseInt(p.getProperty("pitch", "15"));
		offsetX = Integer.parseInt(p.getProperty("offsetX", "0"));
		offsetZ = Integer.parseInt(p.getProperty("offsetZ", "0"));
		biome = p.getProperty("biome");
		fly = Double.parseDouble(p.getProperty("fly", "0"));
		command = p.getProperty("command");
		f3 = "true".equals(p.getProperty("f3"));
	}

	/** A request file older than this is from a benchmark run that never started the game. */
	private static final long STALE_MILLIS = 15 * 60 * 1000;

	/** Reads (and deletes) the request file, if there is one. */
	public static void init() {
		Path file = DIR.resolve("autobench.properties");
		if (!Files.exists(file)) return;
		Properties p = new Properties();
		boolean stale;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			p.load(r);
			// Left by a benchmark run that was stopped before the game started: not for this game, which it would take over.
			stale = Files.getLastModifiedTime(file).toMillis() < System.currentTimeMillis() - STALE_MILLIS;
			Files.delete(file);
		} catch (IOException e) {
			Afterburner.LOGGER.warn("Couldn't read {}", file, e);
			return;
		}
		if (stale) {
			Afterburner.LOGGER.warn("Ignored an old benchmark request ({}): {}", file, p);
			return;
		}
		instance = new AutoBench(p);
		Afterburner.LOGGER.info("Auto benchmark armed: {}", p);
	}

	public static boolean active() {
		return instance != null;
	}

	/** Key presses and clicks ignored during the benchmark. Render thread only. */
	private static int ignoredInput;

	/** True while a benchmark runs: keyboard and mouse input is ignored then. {@code count}: a key press or click. */
	public static boolean ignoresInput(boolean count) {
		if (instance == null) return false;
		if (count) ignoredInput++;
		return true;
	}

	/** True while a benchmark measures, when nothing else should run (the memory trim waits for the pause part). */
	public static boolean busy() {
		return instance != null && instance.phase != Phase.PAUSED && instance.phase != Phase.DONE;
	}

	/** Start of every frame, from the game loop. */
	public static void onFrame(Minecraft mc) {
		if (instance != null) instance.frame(mc, System.nanoTime());
	}

	/** Sections waiting to build, or -1 if another renderer (Sodium) replaced vanilla's. */
	private static int compileQueue(Minecraft mc) {
		try {
			return mc.levelRenderer.sectionRenderDispatcher().getCompileQueueSize();
		} catch (RuntimeException e) {
			return -1;
		}
	}

	private void frame(Minecraft mc, long now) {
		switch (phase) {
			case WAIT_WORLD -> {
				MinecraftServer server = mc.getSingleplayerServer();
				if (mc.level == null || mc.player == null || server == null || mc.gui.screen() != null) return;
				setUp(mc, server);
				joinedAt = spinOrigin = phaseStart = lastChange = now;
				phase = Phase.WARMUP;
				marker("warmup");
			}
			case WARMUP -> {
				spin(mc, now);
				if (now - lastLog > 5_000_000_000L) {
					lastLog = now;
					Afterburner.LOGGER.info("Auto benchmark warmup: {} FPS, {} chunks, {} sections waiting to build", mc.getFps(),
							mc.level.getChunkSource().getLoadedChunksCount(), compileQueue(mc));
				}
				int chunks = mc.level.getChunkSource().getLoadedChunksCount();
				boolean busy = !mc.levelRenderer.hasRenderedAllSections();
				if (BuildLog.ENABLED) BuildLog.frame(now - joinedAt, chunks, busy, compileQueue(mc));
				if (!busy) busySince = 0;
				else if (busySince == 0) busySince = now;
				// A rebuild here and there (water flowing, leaves) isn't loading; only building for a while counts.
				boolean building = busySince != 0 && now - busySince > 50_000_000L;
				if (chunks != lastChunks || building) {
					lastChunks = chunks;
					lastChange = now;
					if (building) wasBusy = true;
				}
				// Ready once a whole circle went by with nothing new loaded or left to build.
				long circle = seconds * 1_000_000_000L;
				boolean settled = now - lastChange > circle && wasBusy;
				boolean timeout = now - joinedAt > 240_000_000_000L;
				if (settled || timeout) {
					readyAt = lastChange;
					if (timeout) Afterburner.LOGGER.warn("Auto benchmark: chunks never settled, measuring anyway");
					phase = Phase.SETTLE;
					phaseStart = now;
				}
			}
			case SETTLE -> {
				// Face the starting direction for a second, take the screenshot, then measure one full circle from there.
				look(mc, 0);
				if (stills < STILLS && now - phaseStart > 500_000_000L) {
					Screenshot.grab(mc.gameDirectory, "autobench-" + label + "-still" + stills + ".png", mc.gameRenderer.mainRenderTarget(), 1, m -> {
					});
					stills++;
				}
				if (now - phaseStart > 1_000_000_000L && stills >= STILLS) {
					Screenshot.grab(mc.gameDirectory, "autobench-" + label + ".png", mc.gameRenderer.mainRenderTarget(), 1, m -> {
					});
					phase = Phase.MEASURE;
					spinOrigin = now;
					flyX = mc.player.getX();
					flyY = mc.player.getY();
					flyZ = mc.player.getZ();
					SectionFaces.drawnQuads = SectionFaces.totalQuads = 0;
					TranslucentCulling.drawnQuads = TranslucentCulling.totalQuads = 0;
					ChunkBatcher.drawCalls = ChunkBatcher.sectionDraws = ChunkBatcher.builds = ChunkBatcher.patches = 0;
					ChunkBatcher.copiedSections = ChunkBatcher.lookedUpSections = 0;
					ChunkBatcher.rebuiltForMeshes = ChunkBatcher.rebuiltForList = ChunkBatcher.rebuiltForMoved = ChunkBatcher.rebuiltForFading = 0;
					ChunkBatcher.rebuiltOther = ChunkBatcher.fadedInPlace = 0;
					ChunkBatcher.swappedFrames = ChunkBatcher.swappedSections = ChunkBatcher.ignoredChanges = 0;
					ChunkBatcher.listsTaken = ChunkBatcher.rebuiltForLeftOut = ChunkBatcher.swapChecks = ChunkBatcher.swapDifferent = 0;
					ChunkBatcher.updateCalls = ChunkBatcher.updateNanos = ChunkBatcher.retakenSections = 0;
					ChunkBatcher.fills = ChunkBatcher.partialFills = ChunkBatcher.drawsFilled = ChunkBatcher.drawsSeen = 0;
					ChunkBatcher.fillChecks = ChunkBatcher.fillDifferent = 0;
					BlockEntitySections.checks = BlockEntitySections.different = 0;
					ChunkBatcher.listedSections = ChunkBatcher.drawnSections = 0;
					ChunkBatcher.lockNanos = ChunkBatcher.slotBreaks = ChunkBatcher.bufferBreaks = ChunkBatcher.indexBreaks = ChunkBatcher.otherBreaks = 0;
					for (long[] a : new long[][] {ChunkBatcher.layerDraws, ChunkBatcher.layerBatches, ChunkBatcher.layerSlots, ChunkBatcher.layerCalls,
							ChunkBatcher.layerSingles}) java.util.Arrays.fill(a, 0);
					AnimatedSprites.updates = AnimatedSprites.redrawn = AnimatedSprites.skipped = 0;
					BiomeMemo.reset();
					BuildTimes.reset();
					LightMemo.reset();
					OcclusionRays.reset();
					LeafCulling.reset();
					VisibilityTimes.reset();
					ChunkBatcher.buildNanos = ChunkBatcher.buildMaxNanos = ChunkBatcher.fillNanos = ChunkBatcher.fillMaxNanos = 0;
					GpuTimers.reset();
					GpuTimers.measuring = true;
					FrameWaits.reset();
					FrameWaits.measuring = true;
					FrameSections.reset();
					FrameSections.measuring = true;
					OcclusionProbe.reset();
					OcclusionCulling.resetStats();
					EntityCulling.resetStats();
					OcclusionCulling.measuring = true;
					OcclusionProbe.measuring = true;
					BenchProfile.start();
					TickParking.reset();
					serverTicks = mc.getSingleplayerServer() != null ? TickRecorder.start() : null;
					if (command != null && mc.getConnection() != null) mc.getConnection().sendCommand(command);
					if (f3) mc.debugEntries.setOverlayVisible(true);
					marker("measure");
					FrameRecorder.start(seconds, run -> {
						// Only the measured circle counts, not the pause menu after it.
						GpuTimers.measuring = false;
						FrameWaits.measuring = false;
						FrameSections.measuring = false;
						OcclusionProbe.measuring = false;
						OcclusionCulling.measuring = false;
						BenchProfile.stop();
						if (serverTicks != null) {
							serverTickTimes = serverTicks.stop();
							serverTps = serverTicks.tps();
						}
						if (pauseSeconds <= 0 || run == null) {
							finish(mc, run);
							return;
						}
						measured = run;
						phase = Phase.PAUSED;
						phaseStart = System.nanoTime();
						mc.pauseGame(false);
						marker("paused");
					});
				}
			}
			case MEASURE -> {
				if (fly > 0) fly(mc, now);
				else spin(mc, now);
				if (f3 && !f3Saved && now - spinOrigin > seconds * 500_000_000L) {
					Screenshot.grab(mc.gameDirectory, "autobench-" + label + "-f3.png", mc.gameRenderer.mainRenderTarget(), 1, m -> {
					});
					f3Saved = true;
				}
				if (BuildLog.ENABLED) BuildLog.frame(now - joinedAt, mc.level.getChunkSource().getLoadedChunksCount(), !mc.levelRenderer.hasRenderedAllSections(), compileQueue(mc));
				// A minimized window is held at 10 FPS, which would make the result meaningless.
				if (mc.getFramerateLimitTracker().getThrottleReason() != FramerateLimitTracker.FramerateThrottleReason.NONE) throttledFrames++;
			}
			case PAUSED -> {
				if (now - phaseStart > pauseSeconds * 1_000_000_000L) finish(mc, measured);
			}
			case SCREENS -> {
				if (now - phaseStart < 2_000_000_000L) return;
				String name = screensShown == 0 ? "video" : "afterburner";
				Screenshot.grab(mc.gameDirectory, "autobench-" + label + "-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1, m -> {
				});
				Afterburner.LOGGER.info("Auto benchmark: saved the {} screen ({})", name, mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getName());
				phaseStart = now;
				if (++screensShown == 1) {
					mc.gui.setScreen(new SettingsScreen(mc.gui.screen(), mc.options));
				} else {
					phase = Phase.DONE;
					mc.stop();
				}
			}
			case DONE -> {
			}
		}
	}

	/** Sodium's settings screen if it's installed (it replaces Video Settings), else vanilla's. */
	private static Screen videoSettings(Minecraft mc) {
		try {
			return (Screen) Class.forName("net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen").getMethod("createScreen", Screen.class).invoke(null, (Object) null);
		} catch (ReflectiveOperationException e) {
			return new VideoSettingsScreen(null, mc, mc.options);
		}
	}

	private void setUp(Minecraft mc, MinecraftServer server) {
		mc.options.renderDistance().set(renderDistance);
		mc.options.simulationDistance().set(Math.min(8, renderDistance));
		mc.options.enableVsync().set(false);
		mc.options.framerateLimit().set(260);
		// Nobody touches the keyboard during a benchmark, so the game would otherwise drop to 30 FPS after a minute.
		mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
		mc.debugEntries.setOverlayVisible(false);
		server.execute(() -> {
			var commands = server.getCommands();
			var source = server.createCommandSourceStack().withSuppressedOutput();
			for (String c : List.of("gamemode creative @a", "time set noon", "weather clear 1000000", "gamerule advance_time false",
					"gamerule advance_weather false", "gamerule spawn_mobs false")) {
				commands.performPrefixedCommand(source, c);
			}
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				// The world's spawn point, not where the player landed: new players land somewhere random around it, so every run
				// (and every mod compared) would see a different scene.
				BlockPos at = server.getRespawnData().pos().offset(offsetX, 0, offsetZ);
				if (biome != null) at = inBiome((ServerLevel) p.level(), at, biome);
				// Loaded first: the height of a chunk that isn't would be the bottom of the world.
				p.level().getChunk(at.getX() >> 4, at.getZ() >> 4);
				int ground = p.level().getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ());
				p.teleportTo(at.getX() + 0.5, ground + altitude, at.getZ() + 0.5);
				p.getAbilities().flying = true;
				p.onUpdateAbilities();
			}
		});
	}

	/**
	 * The middle of the biggest area of that biome within 3000 blocks: every 128 blocks, how much of the 256 x 256 around
	 * is that biome, best first, nearest first among equals. Same world, same answer, so every run stands in the same spot.
	 */
	private static BlockPos inBiome(ServerLevel level, BlockPos from, String name) {
		ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, Identifier.parse(name));
		int y = level.getSeaLevel();
		BlockPos best = null;
		int bestScore = 0;
		long bestDistance = Long.MAX_VALUE;
		for (int cx = -24; cx <= 24; cx++) {
			for (int cz = -24; cz <= 24; cz++) {
				int x = from.getX() + cx * 128, z = from.getZ() + cz * 128, score = 0;
				for (int dx = -2; dx <= 2; dx++) {
					for (int dz = -2; dz <= 2; dz++) {
						if (level.getBiome(new BlockPos(x + dx * 64, y, z + dz * 64)).is(key)) score++;
					}
				}
				long distance = (long) cx * cx + (long) cz * cz;
				if (score > bestScore || score == bestScore && score > 0 && distance < bestDistance) {
					bestScore = score;
					bestDistance = distance;
					best = new BlockPos(x, y, z);
				}
			}
		}
		if (best == null) {
			Afterburner.LOGGER.warn("Benchmark: no {} biome near spawn, standing at spawn", name);
			return from;
		}
		Afterburner.LOGGER.info("Benchmark: standing in {} at {} {} ({}/25 of the area around)", name, best.getX(), best.getZ(), bestScore);
		return best;
	}

	/** One full turn every {@code seconds}, looking a little down at the horizon (or {@code pitch} degrees down). */
	private void spin(Minecraft mc, long now) {
		double turns = (now - spinOrigin) / (seconds * 1e9);
		if (phase == Phase.WARMUP) circles = (int) turns;
		look(mc, (float) (turns % 1.0 * 360.0));
	}

	/** Straight south (where {@link #look} 0 faces) at {@link #fly} blocks a second, at the height measuring started at. */
	private void fly(Minecraft mc, long now) {
		if (mc.player == null) return;
		mc.player.setDeltaMovement(Vec3.ZERO);
		mc.player.snapTo(flyX, flyY, flyZ + (now - spinOrigin) / 1e9 * fly, 0, pitch);
	}

	private void look(Minecraft mc, float yaw) {
		if (mc.player == null) return;
		mc.player.setYRot(yaw);
		mc.player.yRotO = yaw;
		mc.player.setXRot(pitch);
		mc.player.xRotO = pitch;
	}

	/** Tells tools/clientbench.py which part of the run the game is in, so it can tag its RAM samples. */
	private static void marker(String name) {
		try {
			Files.writeString(DIR.resolve("autobench-phase.txt"), name);
		} catch (IOException ignored) {
		}
	}

	private void finish(Minecraft mc, FrameRecorder.Run run) {
		phase = Phase.DONE;
		GpuTimers.measuring = false;
		marker("done");
		if (run == null) {
			Afterburner.LOGGER.warn("Auto benchmark: left the world before it finished");
			return;
		}
		Stats f = Stats.of(run.frames());
		DeviceInfo gpu = RenderSystem.getDevice().getDeviceInfo();
		double fps = f.count() / (f.totalMs() / 1000);
		double ready = (readyAt - joinedAt) / 1e9;
		List<String> lines = new ArrayList<>();
		lines.add("Auto benchmark \"" + label + "\": render distance " + renderDistance + ", " + mc.getWindow().getWidth() + "x"
				+ mc.getWindow().getHeight());
		lines.add("FPS avg " + Reports.f0(fps) + " | 1% low " + Reports.f0(Stats.fps(f.p99Ms())) + " | 0.1% low "
				+ Reports.f0(Stats.fps(f.p999Ms())) + " (" + Reports.f0(f.count()) + " frames)");
		lines.add("Frame time avg " + Reports.f2(f.avgMs()) + " ms | 99% " + Reports.f2(f.p99Ms()) + " | max " + Reports.f2(f.maxMs())
				+ " | stutters over 50 ms: " + f.over50Ms());
		lines.add("All chunks loaded and built " + Reports.f1(ready) + " s after joining (" + circles + " circles of warmup), "
				+ mc.level.getChunkSource().getLoadedChunksCount() + " chunks, " + mc.levelRenderer.visibleSections().size()
				+ " sections visible at the end");
		if (serverTickTimes != null && serverTickTimes.length > 0) {
			for (String line : TickCommand.tickLines(Stats.of(serverTickTimes), serverTps)) lines.add("Server " + line);
		}
		if (Features.PARKED_TICKS.enabled()) lines.add(TickParking.summary());
		boolean mdi = mc.levelRenderer.isChunkRenderingUsingMultiDrawIndirect();
		double skipped = SectionFaces.totalQuads == 0 ? 0 : 100.0 * (1 - (double) SectionFaces.drawnQuads / SectionFaces.totalQuads);
		lines.add("GPU: " + gpu.name() + " (" + gpu.backendName() + (mdi ? ", multi-draw" : ", separate draws") + "), " + Reports.modsLine());
		if (ignoredInput > 0) lines.add("Ignored " + ignoredInput + " key presses and clicks during the run (the window had focus)");
		if (throttledFrames > 0) lines.add("WARNING: " + throttledFrames + " frames were slowed down by the game (window minimized?), the result is not valid");
		if (SectionFaces.totalQuads > 0) lines.add("Face culling left out " + Reports.f1(skipped) + "% of opaque quads");
		if (TranslucentCulling.totalQuads > 0) lines.add("Translucent face culling left out "
				+ Reports.f1(100.0 * (1 - (double) TranslucentCulling.drawnQuads / TranslucentCulling.totalQuads)) + "% of water, glass and ice quads");
		if (OcclusionCulling.testedSections > 0) lines.add("Occlusion culling: " + Reports.f1(100.0 * OcclusionCulling.hiddenSections / OcclusionCulling.testedSections)
				+ "% of sections hidden behind nearer terrain (" + OcclusionCulling.testedFrames + " frames tested), " + Reports.f0((double) OcclusionCulling.leftOutSections / f.count())
				+ " sections held back or left out per frame, " + Reports.f0((double) OcclusionCulling.lateCommands / f.count()) + " held-back draws per frame");
		if (OcclusionCulling.testedObjects > 0) lines.add("Entity culling: " + Reports.f1(100.0 * OcclusionCulling.hiddenObjects / OcclusionCulling.testedObjects)
				+ "% of entity and block entity tests hidden, left out per frame: " + Reports.f1((double) EntityCulling.culledEntities / f.count())
				+ " entities, " + Reports.f1((double) EntityCulling.culledBlockEntities / f.count()) + " block entities");
		if (OffscreenBuilds.built > 0) lines.add("Off-screen chunk building: " + OffscreenBuilds.built + " sections built before they were on screen");
		if (ChunkBatcher.drawCalls > 0) lines.add("Chunk batching: " + Reports.f0((double) ChunkBatcher.drawCalls / f.count()) + " draw calls per frame for "
				+ Reports.f0((double) ChunkBatcher.sectionDraws / f.count()) + " section draws, built anew in "
				+ Reports.f0(100.0 * ChunkBatcher.builds / f.count()) + "% of frames (copying " + Reports.f0(100.0 * ChunkBatcher.copiedSections
				/ Math.max(1, ChunkBatcher.copiedSections + ChunkBatcher.lookedUpSections)) + "% of sections from the frame before), patched in "
				+ Reports.f0(100.0 * ChunkBatcher.patches / f.count()) + "% (" + Reports.f1((double) ChunkBatcher.retakenSections / f.count())
				+ " sections per frame taken in again), fading kept in place in " + Reports.f0(100.0 * ChunkBatcher.fadedInPlace / f.count())
				+ "%, new meshes swapped in in " + Reports.f0(100.0 * ChunkBatcher.swappedFrames / f.count()) + "% ("
				+ Reports.f1((double) ChunkBatcher.swappedSections / Math.max(1, ChunkBatcher.swappedFrames)) + " sections each), new lists taken in place in "
				+ Reports.f0(100.0 * ChunkBatcher.listsTaken / f.count()) + "% (updates in place " + Reports.f2(ChunkBatcher.updateNanos / 1e6 / Math.max(1, ChunkBatcher.updateCalls))
				+ " ms each), "
				+ Reports.f1((double) ChunkBatcher.ignoredChanges / f.count()) + " changes per frame outside the list left alone"
				+ (ChunkBatcher.swapChecks > 0 ? " (check: " + ChunkBatcher.swapChecks + " compared, " + ChunkBatcher.swapDifferent + " different)" : "")
				+ "; built anew for other mesh changes " + Reports.f0(100.0 * ChunkBatcher.rebuiltForMeshes / f.count()) + "%, another list "
				+ Reports.f0(100.0 * ChunkBatcher.rebuiltForList / f.count()) + "%, moved meshes " + Reports.f0(100.0 * ChunkBatcher.rebuiltForMoved / f.count())
				+ "%, fading " + Reports.f0(100.0 * ChunkBatcher.rebuiltForFading / f.count()) + "%, too much left out " + Reports.f0(100.0 * ChunkBatcher.rebuiltForLeftOut / f.count())
				+ "%, other " + Reports.f0(100.0 * ChunkBatcher.rebuiltOther / f.count()) + "%"
				+ " | build " + Reports.f2(ChunkBatcher.buildNanos / 1e6 / Math.max(1, ChunkBatcher.builds)) + " ms each (max " + Reports.f2(ChunkBatcher.buildMaxNanos / 1e6)
				+ "), fill " + Reports.f2(ChunkBatcher.fillNanos / 1e6 / f.count()) + " ms per frame (max " + Reports.f2(ChunkBatcher.fillMaxNanos / 1e6) + "; "
				+ Reports.f0(100.0 * ChunkBatcher.partialFills / Math.max(1, ChunkBatcher.fills)) + "% of fills left batches as they were, "
				+ Reports.f0(100.0 * ChunkBatcher.drawsFilled / Math.max(1, ChunkBatcher.drawsSeen)) + "% of draws filled"
				+ (ChunkBatcher.fillChecks > 0 ? ", check: " + ChunkBatcher.fillChecks + " compared, " + ChunkBatcher.fillDifferent + " different" : "") + ")"
				+ (BlockEntitySections.checks > 0 ? ", block entity sections check: " + BlockEntitySections.checks + " compared, " + BlockEntitySections.different
				+ " different" : ""));
		if (ChunkBatcher.builds > 0) {
			StringBuilder b = new StringBuilder("Chunk batching per build: " + Reports.f0((double) ChunkBatcher.listedSections / ChunkBatcher.builds) + " sections listed, "
					+ Reports.f0((double) ChunkBatcher.drawnSections / ChunkBatcher.builds) + " with draws;");
			for (net.minecraft.client.renderer.chunk.ChunkSectionLayer layer : net.minecraft.client.renderer.chunk.ChunkSectionLayer.values()) {
				int l = layer.ordinal();
				b.append(" ").append(layer.name().toLowerCase()).append(" ").append(Reports.f0((double) ChunkBatcher.layerDraws[l] / ChunkBatcher.builds)).append(" draws, ")
						.append(Reports.f0((double) ChunkBatcher.layerBatches[l] / ChunkBatcher.builds)).append(" batches, ")
						.append(Reports.f0((double) ChunkBatcher.layerSlots[l] / ChunkBatcher.builds)).append(" slots, calls per frame ")
						.append(Reports.f0((double) ChunkBatcher.layerCalls[l] / f.count())).append(" (").append(Reports.f0((double) ChunkBatcher.layerSingles[l] / f.count()))
						.append(" single);");
			}
			b.append(" lock waits ").append(Reports.f2(ChunkBatcher.lockNanos / 1e6 / ChunkBatcher.builds)).append(" ms per build; ordered batches ended by another slot ")
					.append(Reports.f0((double) ChunkBatcher.slotBreaks / ChunkBatcher.builds)).append(", by other vertex buffers ").append(Reports.f0((double) ChunkBatcher.bufferBreaks / ChunkBatcher.builds))
					.append(", index buffers ").append(Reports.f0((double) ChunkBatcher.indexBreaks / ChunkBatcher.builds))
					.append(", else ").append(Reports.f0((double) ChunkBatcher.otherBreaks / ChunkBatcher.builds));
			lines.add(b.toString());
		}
		if (AnimatedSprites.updates > 0) lines.add("Visible-only animations: per update " + Reports.f1((double) AnimatedSprites.redrawn / AnimatedSprites.updates)
				+ " block textures redrawn, " + Reports.f1((double) AnimatedSprites.skipped / AnimatedSprites.updates) + " left out (" + AnimatedSprites.updates + " updates)");
		lines.add(BuildTimes.line());
		lines.add(VisibilityTimes.line());
		if (Features.FAST_BIOME_BLEND.enabled()) lines.add(BiomeMemo.summary());
		if (Features.SMOOTH_LIGHT_CACHE.enabled()) lines.add(LightMemo.summary());
		if (Features.FAST_VISIBILITY.enabled()) lines.add(OcclusionRays.summary());
		if (Features.LEAF_CULLING.enabled()) lines.add(LeafCulling.summary());
		lines.addAll(GpuTimers.lines());
		lines.addAll(FrameWaits.lines());
		lines.addAll(FrameSections.lines());
		lines.addAll(OcclusionProbe.lines());
		String memory = MemoryReport.write("autobench-" + label) + " | " + ChunkBufferStats.line(mc);
		lines.add(memory);
		Reports.write("autobench-" + label, lines);
		double[] cpu = FrameSections.fps();
		String json = String.format(Locale.ROOT,
				"{\"label\":\"%s\",\"rd\":%d,\"fps\":%.1f,\"low1\":%.1f,\"low01\":%.1f,\"avgMs\":%.3f,\"p99Ms\":%.3f,\"maxMs\":%.2f,"
						+ "\"stutters\":%d,\"frames\":%d,\"readyS\":%.1f,\"mdi\":%b,\"culledPct\":%.1f,\"throttled\":%d,\"features\":\"%s\",\"memory\":\"%s\","
						+ "\"cpuFps\":%.1f,\"cpuLow1\":%.1f,\"cpuLow01\":%.1f}",
				label, renderDistance, fps, Stats.fps(f.p99Ms()), Stats.fps(f.p999Ms()), f.avgMs(), f.p99Ms(), f.maxMs(),
				f.over50Ms(), f.count(), ready, mdi, skipped, throttledFrames, Features.summary(), memory, cpu[0], cpu[1], cpu[2]);
		try {
			Files.writeString(DIR.resolve("autobench-results.jsonl"), json + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			Afterburner.LOGGER.warn("Couldn't save auto benchmark results", e);
		}
		Afterburner.LOGGER.info("Auto benchmark: {}", String.join(" | ", lines));
		if (SCREENS && quit) {
			phase = Phase.SCREENS;
			phaseStart = System.nanoTime();
			mc.gui.setScreen(videoSettings(mc));
			return;
		}
		if (quit) mc.stop();
	}
}
