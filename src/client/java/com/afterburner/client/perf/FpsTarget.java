package com.afterburner.client.perf;

import com.afterburner.client.Addons;
import com.afterburner.client.render.WideSections;
import com.mojang.blaze3d.platform.Monitor;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Target FPS, for slow computers: when frames come slower than the target, one thing at a time is lowered, what costs the
 * most for the least seen first: the far terrain's distance, then clouds, then how far chunks are drawn (the fog comes
 * nearer; the chunks stay loaded, so nothing is built again when it goes back). When there's been room for a while, they're
 * raised back the same way. The player's own settings aren't changed or saved: these are limits on what's drawn, and turning
 * the target off lifts them all. If lowering everything barely helps, what's slow is something else, and it's all put back
 * for a while. Kept in config/afterburner-fps.properties.
 * <p>
 * Frames are timed from the start of one to the next, leaving out stutters (no setting here helps those). Whether there's
 * room is judged without the time spent waiting for an FPS limit or VSync, which hold the frame rate down on purpose.
 * Render thread only.
 */
public final class FpsTarget {
	private static final Logger LOGGER = LoggerFactory.getLogger("afterburner");
	private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("afterburner-fps.properties");
	/** The choices, in FPS; 0 is off. */
	public static final int[] CHOICES = {0, 30, 40, 50, 60, 75, 90, 120, 144};
	/** No limit. */
	public static final int NONE = Integer.MAX_VALUE;
	/** Chunks are drawn at least this far. */
	private static final int MIN_DRAW = 6;
	/** The far terrain is turned off rather than brought nearer than this (in chunks). */
	private static final int MIN_FAR = 32;
	/** The far terrain's distance is lowered in steps this big (in chunks), as its slider moves. */
	private static final int FAR_STEP = 16;
	/** Frames slower than this are stutters (a chunk loading, the garbage collector), left out. */
	private static final long STUTTER = 250_000_000L;
	/** Frames are judged in windows this long. */
	private static final long WINDOW = 500_000_000L;
	/** Under this much of the target for {@link #LOW_WINDOWS} windows in a row: a step down. */
	private static final double LOW = 0.95;
	/** Room for this much more than the target for {@link #ROOM_WINDOWS} windows in a row: a step back up. */
	private static final double ROOM = 1.25;
	private static final int LOW_WINDOWS = 2, ROOM_WINDOWS = 16;
	/** After a change, how long until the next step down (the change shows first), and until a step up. */
	private static final long SETTLE_DOWN = 2_000_000_000L, SETTLE_UP = 8_000_000_000L;
	/** A step down this soon after a step up: that step up was too much, and isn't taken again for {@link #HOLD}. */
	private static final long TOO_SOON = 20_000_000_000L, HOLD = 120_000_000_000L;
	/** Not judged this long after joining a world or changing dimension (chunks loading). */
	private static final long GRACE = 10_000_000_000L;
	/**
	 * With everything lowered, frames must come at least this much faster than before the first step; if not, what holds them
	 * back is something these don't change (the world's ticks, mobs, other mods): all is put back, and kept for {@link #GIVE_UP}.
	 */
	private static final double HELPED = 1.1;
	private static final long GIVE_UP = 300_000_000_000L;

	private static int target = read();

	/** The steps, from 0 (nothing lowered): the far terrain's distance, whether clouds are off, and the chunk distance drawn. */
	private static int[] farSteps = {NONE}, drawSteps = {NONE};
	private static boolean[] cloudSteps = {false};
	/** The player's settings the steps were made for. */
	private static long plannedFor = Long.MIN_VALUE;
	private static int level, floor;
	private static long floorUntil, changedAt, steppedUpAt, graceUntil, gaveUpUntil;
	/** The frame rate when the first step down was taken. */
	private static double topFps;
	private static @Nullable ClientLevel lastLevel;

	private static int farCap = NONE, drawCap = NONE;
	private static boolean cloudsOff;
	private static boolean drawChanged;

	private static long lastFrame, windowStart, windowTime, windowIdle, idleThisFrame;
	private static int windowFrames, lowWindows, roomWindows;

	private FpsTarget() {
	}

	/** The target, in FPS; 0 when off. */
	public static int target() {
		return target;
	}

	/** Sets it (one of {@link #CHOICES}) and saves it. */
	public static void set(int fps) {
		if (fps == target) return;
		target = fps;
		save();
		windowStart = 0;
		plannedFor = Long.MIN_VALUE;
		apply(0, System.nanoTime());
	}

	/** How far the far terrain may reach, in chunks ({@link #NONE}: as set). */
	public static int farCap() {
		return farCap;
	}

	/** Whether clouds are left out. */
	public static boolean cloudsOff() {
		return cloudsOff;
	}

	/** How far chunks are drawn, in chunks ({@link #NONE}: the render distance). */
	public static int drawCap() {
		return drawCap;
	}

	/** True once after the chunk distance drawn changed, so vanilla picks its visible sections again. */
	public static boolean consumeDrawChange() {
		boolean changed = drawChanged;
		drawChanged = false;
		return changed;
	}

	/** The render distance for the fog, in chunks: the chunk distance drawn. */
	public static int fogDistance(int renderDistance) {
		return Math.min(renderDistance, drawCap);
	}

	/** Whether chunk ({@code x}, {@code z}) is drawn, seen from chunk ({@code cx}, {@code cz}); vanilla's render distance test. */
	public static boolean drawn(int cx, int cz, int x, int z) {
		int cap = drawCap;
		if (cap == NONE) return true;
		long dx = Math.max(0, Math.abs(x - cx) - 1), dz = Math.max(0, Math.abs(z - cz) - 1);
		return dx * dx + dz * dz < (long) cap * cap;
	}

	/** Takes the sections further than the chunk distance drawn out of a list, keeping the order of the rest. */
	public static void filter(List<SectionRenderDispatcher.RenderSection> list, double camX, double camZ) {
		if (drawCap == NONE) return;
		int cx = Mth.floor(camX) >> 4, cz = Mth.floor(camZ) >> 4;
		if (list instanceof ObjectArrayList<SectionRenderDispatcher.RenderSection> array) {
			Object[] a = array.elements();
			int n = array.size(), m = 0;
			for (int i = 0; i < n; i++) {
				long node = ((SectionRenderDispatcher.RenderSection) a[i]).getSectionNode();
				if (drawn(cx, cz, SectionPos.x(node), SectionPos.z(node))) a[m++] = a[i];
			}
			array.size(m);
		} else {
			list.removeIf(s -> !drawn(cx, cz, SectionPos.x(s.getSectionNode()), SectionPos.z(s.getSectionNode())));
		}
	}

	/** Time this frame spent waiting on purpose (an FPS limit, VSync). */
	public static void idle(long nanos) {
		idleThisFrame += nanos;
	}

	/** Whether waits are timed now (for {@link #idle}). */
	public static boolean timing() {
		return target != 0;
	}

	/** Start of every frame. */
	public static void frame(Minecraft mc) {
		long now = System.nanoTime();
		long dt = now - lastFrame, idle = idleThisFrame;
		lastFrame = now;
		idleThisFrame = 0;
		if (target == 0) return;
		if (mc.level != lastLevel) {
			lastLevel = mc.level;
			graceUntil = now + GRACE;
			windowStart = 0;
		}
		if (mc.level == null || mc.player == null || mc.gui.screen() != null || !mc.isWindowActive() || now < graceUntil) {
			windowStart = 0;
			return;
		}
		long key = settings(mc);
		if (key != plannedFor) {
			plannedFor = key;
			plan(mc);
			floor = 0;
			floorUntil = gaveUpUntil = 0;
			apply(0, now);
		}
		if (windowStart == 0) {
			windowStart = now;
			windowTime = windowIdle = 0;
			windowFrames = lowWindows = roomWindows = 0;
			return;
		}
		if (dt <= 0 || dt > STUTTER) return;
		windowTime += dt;
		windowIdle += Math.min(idle, dt);
		windowFrames++;
		if (now - windowStart < WINDOW) return;
		judge(mc, now);
		windowStart = now;
		windowTime = windowIdle = 0;
		windowFrames = 0;
	}

	private static void judge(Minecraft mc, long now) {
		if (windowFrames == 0 || windowTime <= 0) return;
		double fps = windowFrames * 1e9 / windowTime;
		double free = windowFrames * 1e9 / Math.max(1, windowTime - windowIdle);
		double goal = goal(mc);
		if (fps < goal * LOW) {
			lowWindows++;
			roomWindows = 0;
		} else if (free > goal * ROOM) {
			roomWindows++;
			lowWindows = 0;
		} else {
			lowWindows = roomWindows = 0;
		}
		if (floorUntil != 0 && now > floorUntil) {
			floor = 0;
			floorUntil = 0;
		}
		int last = farSteps.length - 1;
		if (lowWindows >= LOW_WINDOWS && now - changedAt > SETTLE_DOWN && now > gaveUpUntil) {
			if (level < last) {
				if (level == 0) topFps = fps;
				if (now - steppedUpAt < TOO_SOON) {
					floor = level + 1;
					floorUntil = now + HOLD;
				}
				apply(level + 1, now);
				log(fps, goal);
			} else if (level > 0 && fps < topFps * HELPED) {
				gaveUpUntil = now + GIVE_UP;
				apply(0, now);
				LOGGER.info("[Afterburner] Target FPS {}: {} FPS with everything lowered, {} before; it's held back by something else, so nothing is lowered for {} minutes",
						target, Math.round(fps), Math.round(topFps), GIVE_UP / 60_000_000_000L);
			}
		} else if (roomWindows >= ROOM_WINDOWS && level > floor && now - changedAt > SETTLE_UP) {
			apply(level - 1, now);
			steppedUpAt = now;
			log(free, goal);
		}
	}

	/** The target, or less when an FPS limit or VSync holds frames below it. */
	private static double goal(Minecraft mc) {
		double goal = target;
		int limit = mc.getFramerateLimitTracker().getFramerateLimit();
		if (limit < 260) goal = Math.min(goal, limit);
		if (mc.options.enableVsync().get()) {
			Monitor monitor = mc.getWindow().findBestMonitor();
			if (monitor != null && monitor.currentMode().getRefreshRate() > 0) goal = Math.min(goal, monitor.currentMode().getRefreshRate());
		}
		return goal;
	}

	/** What the steps depend on: render distance, far terrain distance (if it's drawn), clouds, a shader pack. */
	private static long settings(Minecraft mc) {
		int far = Addons.farTerrainChunks();
		boolean clouds = mc.options.cloudStatus().get() != CloudStatus.OFF;
		return (long) mc.options.getEffectiveRenderDistance() << 32 | (long) far << 2 | (clouds ? 2 : 0) | (Addons.shaderPackActive() ? 1 : 0);
	}

	/** Makes the steps for the player's settings now. */
	private static void plan(Minecraft mc) {
		int rd = mc.options.getEffectiveRenderDistance();
		int far = Addons.farTerrainChunks();
		boolean clouds = mc.options.cloudStatus().get() != CloudStatus.OFF;
		List<int[]> steps = new ArrayList<>();
		int f = NONE, d = NONE;
		int c = 0;
		steps.add(new int[] {f, c, d});
		// The far terrain is only drawn past the render distance.
		if (far > rd) {
			int x = far;
			while (x > MIN_FAR) {
				x = Math.max(MIN_FAR, x / 2 / FAR_STEP * FAR_STEP);
				if (x <= rd) break;
				steps.add(new int[] {f = x, c, d});
			}
		}
		if (clouds) steps.add(new int[] {f, c = 1, d});
		if (far > rd) steps.add(new int[] {f = 0, c, d});
		// A shader pack draws its own fog, which wouldn't hide where the chunks end. The far terrain stays off from here: it
		// leaves out where the game's chunks are loaded, drawn or not.
		if (!Addons.shaderPackActive()) {
			int x = rd;
			while (x > MIN_DRAW) {
				x = Math.max(MIN_DRAW, (int) (x * 0.8));
				steps.add(new int[] {0, c, d = x});
			}
		}
		int n = steps.size();
		farSteps = new int[n];
		drawSteps = new int[n];
		cloudSteps = new boolean[n];
		for (int i = 0; i < n; i++) {
			farSteps[i] = steps.get(i)[0];
			cloudSteps[i] = steps.get(i)[1] != 0;
			drawSteps[i] = steps.get(i)[2];
		}
	}

	private static void apply(int to, long now) {
		level = Math.clamp(to, 0, farSteps.length - 1);
		changedAt = now;
		lowWindows = roomWindows = 0;
		int draw = target == 0 ? NONE : drawSteps[level];
		farCap = target == 0 ? NONE : farSteps[level];
		cloudsOff = target != 0 && cloudSteps[level];
		if (draw != drawCap) {
			drawCap = draw;
			drawChanged = true;
			WideSections.graphChanged();
		}
	}

	private static void log(double fps, double goal) {
		LOGGER.info("[Afterburner] Target FPS {}: {} FPS against {}, now {}", target, Math.round(fps), Math.round(goal), describe());
	}

	/** What's lowered, for the logs and the benchmark report. */
	public static String describe() {
		if (target == 0) return "off";
		List<String> parts = new ArrayList<>();
		if (farCap != NONE) parts.add(farCap == 0 ? "far terrain off" : "far terrain " + farCap + " chunks");
		if (cloudsOff) parts.add("clouds off");
		if (drawCap != NONE) parts.add("chunks drawn to " + drawCap);
		if (System.nanoTime() < gaveUpUntil) parts.add("lowering didn't help, so it's paused");
		return "step " + level + " of " + (farSteps.length - 1) + (parts.isEmpty() ? ", nothing lowered" : ": " + String.join(", ", parts));
	}

	private static int read() {
		Properties p = new Properties();
		if (Files.exists(CONFIG)) {
			try (Reader in = Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8)) {
				p.load(in);
			} catch (IOException | IllegalArgumentException e) {
				return 0;
			}
		}
		try {
			int fps = Integer.parseInt(p.getProperty("target", "0").strip());
			for (int choice : CHOICES) if (choice == fps) return fps;
			return 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static void save() {
		Properties p = new Properties();
		p.setProperty("target", Integer.toString(target));
		try (Writer out = Files.newBufferedWriter(CONFIG, StandardCharsets.UTF_8)) {
			p.store(out, "Afterburner target FPS: 0 (off) or one of 30, 40, 50, 60, 75, 90, 120, 144");
		} catch (IOException e) {
			LOGGER.warn("[Afterburner] Couldn't save {}: {}", CONFIG, e.toString());
		}
	}
}
