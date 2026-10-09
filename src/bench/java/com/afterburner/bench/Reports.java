package com.afterburner.bench;

import com.afterburner.Afterburner;
import com.afterburner.Features;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Number formatting and the result files in {@code <game dir>/afterburner/benchmarks}. */
public final class Reports {
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private Reports() {
	}

	public static String f1(double v) {
		return String.format(Locale.ROOT, "%.1f", v);
	}

	public static String f2(double v) {
		return String.format(Locale.ROOT, "%.2f", v);
	}

	public static String f0(double v) {
		return String.format(Locale.ROOT, "%,.0f", v);
	}

	/** Writes the lines to a new file and returns its path relative to the game directory, or null if it failed. */
	public static String write(String prefix, List<String> lines) {
		Path game = FabricLoader.getInstance().getGameDir();
		Path file = game.resolve("afterburner").resolve("benchmarks")
				.resolve(prefix + "-" + LocalDateTime.now().format(STAMP) + ".txt");
		try {
			Files.createDirectories(file.getParent());
			Files.write(file, lines);
			return game.relativize(file).toString().replace('\\', '/');
		} catch (IOException e) {
			Afterburner.LOGGER.warn("Couldn't save the benchmark to {}", file, e);
			return null;
		}
	}

	public static String modsLine() {
		FabricLoader loader = FabricLoader.getInstance();
		return "Mods: " + loader.getAllMods().size() + " loaded, Sodium " + (loader.isModLoaded("sodium") ? "yes" : "no")
				+ ", Lithium " + (loader.isModLoaded("lithium") ? "yes" : "no") + ". Afterburner: " + Features.summary();
	}
}
