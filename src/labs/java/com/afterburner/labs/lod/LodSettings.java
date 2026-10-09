package com.afterburner.labs.lod;

import com.afterburner.client.perf.FpsTarget;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * How far the far terrain ({@link Lod}) reaches, and whether land never seen is made from the seed ({@link LodGenerator}),
 * kept in config/afterburner-lod.properties.
 */
public final class LodSettings {
	private static final Logger LOGGER = LoggerFactory.getLogger("afterburner");
	private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("afterburner-lod.properties");
	/** The choices, in chunks: 0 (off), or {@link #MIN} to {@link #MAX} in steps of {@link #STEP}. */
	public static final int MIN = 16, MAX = 1024, STEP = 16;
	private static final int DEFAULT = 128;
	private static int distance = DEFAULT;
	private static volatile boolean unseenLand = true;

	static {
		read();
	}

	private LodSettings() {
	}

	/** In chunks; 0 when off. */
	public static int distance() {
		return distance;
	}

	/** How far it's drawn, in chunks: the distance, or less while {@link FpsTarget} holds it nearer (0: not drawn). */
	public static int drawn() {
		return Math.min(distance, FpsTarget.farCap());
	}

	/** Whether land never seen is made from the world's seed (single player). */
	public static boolean unseenLand() {
		return unseenLand;
	}

	public static void setUnseenLand(boolean on) {
		if (on == unseenLand) return;
		unseenLand = on;
		save();
	}

	/** Sets it (in chunks, 0 for off), as the nearest choice, and saves it. */
	public static void set(int chunks) {
		int value = valid(chunks);
		if (value == distance) return;
		distance = value;
		save();
	}

	private static int valid(int chunks) {
		if (chunks <= 0) return 0;
		return Math.clamp(Math.round(chunks / (float) STEP) * STEP, MIN, MAX);
	}

	private static void read() {
		Properties p = new Properties();
		if (Files.exists(CONFIG)) {
			try (Reader in = Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8)) {
				p.load(in);
			} catch (IOException | IllegalArgumentException e) {
				return;
			}
		}
		try {
			distance = valid(Integer.parseInt(p.getProperty("distance", Integer.toString(DEFAULT)).strip()));
		} catch (NumberFormatException e) {
			distance = DEFAULT;
		}
		unseenLand = !"false".equalsIgnoreCase(p.getProperty("unseenLand", "true").strip());
	}

	private static void save() {
		Properties p = new Properties();
		p.setProperty("distance", Integer.toString(distance));
		p.setProperty("unseenLand", Boolean.toString(unseenLand));
		try (Writer out = Files.newBufferedWriter(CONFIG, StandardCharsets.UTF_8)) {
			p.store(out, "Afterburner far terrain: distance in chunks, 0 (off) or " + MIN + " to " + MAX + " in steps of " + STEP
					+ "; unseenLand: land never seen made from the seed (single player)");
		} catch (IOException e) {
			LOGGER.warn("[Afterburner] Couldn't save {}: {}", CONFIG, e.toString());
		}
	}
}
