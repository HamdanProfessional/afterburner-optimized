package com.afterburner.client;

import com.afterburner.client.gui.SettingsScreen;
import net.fabricmc.loader.api.FabricLoader;

import java.util.List;

/** The {@link AfterburnerAddon}s installed (read once), and what they say together. */
public final class Addons {
	private static final List<AfterburnerAddon> ALL = List.copyOf(FabricLoader.getInstance().getEntrypoints("afterburner", AfterburnerAddon.class));

	private Addons() {
	}

	public static boolean shaderPackActive() {
		for (AfterburnerAddon addon : ALL) {
			if (addon.shaderPackActive()) return true;
		}
		return false;
	}

	public static int farTerrainChunks() {
		int chunks = 0;
		for (AfterburnerAddon addon : ALL) chunks = Math.max(chunks, addon.farTerrainChunks());
		return chunks;
	}

	public static void addSettings(SettingsScreen.Rows rows) {
		for (AfterburnerAddon addon : ALL) addon.addSettings(rows);
	}
}
