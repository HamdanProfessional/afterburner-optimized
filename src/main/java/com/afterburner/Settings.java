package com.afterburner;

import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The player's (or server owner's) choices, kept in {@code config/afterburner.properties}: one {@code key=true/false}
 * line per {@link Features} entry. Read once when the game starts, before any optimization is put in, and written
 * back by the settings screen. Kept apart from {@link Afterburner} so reading it loads no game class this early.
 */
public final class Settings {
	private static final Logger LOGGER = LoggerFactory.getLogger("afterburner");
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("afterburner.properties");
	private static final Properties values = new Properties();
	private static boolean loaded;

	private Settings() {
	}

	/** What the file says for this key; on if it doesn't say. */
	public static synchronized boolean get(String key) {
		load();
		return !"false".equalsIgnoreCase(values.getProperty(key, "true").trim());
	}

	public static synchronized void set(String key, boolean on) {
		load();
		values.setProperty(key, Boolean.toString(on));
	}

	private static void load() {
		if (loaded) return;
		loaded = true;
		if (Files.exists(FILE)) {
			try (Reader in = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
				values.load(in);
			} catch (IOException | IllegalArgumentException e) {
				LOGGER.warn("Couldn't read {}, every optimization stays on: {}", FILE, e.toString());
				values.clear();
				return;
			}
			// A file from an older version has no line for the newer optimizations: add them, so they can be found.
			for (Features f : Features.values()) {
				if (!values.containsKey(f.key())) {
					save();
					return;
				}
			}
		} else {
			save();
		}
	}

	/** Writes every setting, in the order of {@link Features}, each with a line saying what it is. */
	public static synchronized void save() {
		load();
		List<String> lines = new ArrayList<>();
		lines.add("# Afterburner settings: true = on, false = off. Also in game: Options > Video Settings > Afterburner.");
		lines.add("# Read when the game or server starts, so restart after changing this file.");
		lines.add("# On a server only the \"server and client\" ones do anything. In singleplayer all of them do.");
		Features.Group group = null;
		for (Features f : Features.values()) {
			if (f.group() != group) {
				group = f.group();
				lines.add("");
				lines.add("# " + group.title);
			}
			lines.add("# " + f.label() + " (" + f.group().side + ")");
			lines.add(f.key() + "=" + get(f.key()));
		}
		try {
			Files.createDirectories(FILE.getParent());
			Files.write(FILE, lines, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.warn("Couldn't save {}: {}", FILE, e.toString());
		}
	}
}
