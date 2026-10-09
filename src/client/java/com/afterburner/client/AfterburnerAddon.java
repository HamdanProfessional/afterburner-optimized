package com.afterburner.client;

import com.afterburner.client.gui.SettingsScreen;

/**
 * A mod made to go with Afterburner, listed under the {@code "afterburner"} entrypoint of its fabric.mod.json, tells
 * Afterburner what it changes about drawing (all together: {@link Addons}). The shader pack loader and far terrain we're
 * still working on (src/labs, mod {@code afterburner_labs}, not released) plug in this way. Without one, nothing here
 * changes anything.
 */
public interface AfterburnerAddon {
	/** Whether a shader pack draws the world now. Its depth isn't the game's, so occlusion and entity culling stay out. */
	default boolean shaderPackActive() {
		return false;
	}

	/** How far terrain past the render distance is drawn, in chunks (0 for none), for the FPS target to bring nearer. */
	default int farTerrainChunks() {
		return 0;
	}

	/** Puts its rows on Afterburner's settings page, under the FPS target. */
	default void addSettings(SettingsScreen.Rows rows) {
	}
}
