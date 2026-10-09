package com.afterburner.labs;

import net.fabricmc.loader.api.FabricLoader;

import java.util.stream.Stream;

/**
 * Which parts of the labs are in, decided once at startup. The far terrain stays out with {@code -Dafterburner.disable=true}
 * or {@code -Dafterburner.farTerrain=false}, and with Voxy or Distant Horizons (which draw their own) or Sodium (whose chunk
 * renderer it isn't made for). Its distance slider turns it off too (0), but its mixins stay in then.
 */
public final class LabsFeatures {
	public static final boolean FAR_TERRAIN = !Boolean.getBoolean("afterburner.disable")
			&& !"false".equalsIgnoreCase(System.getProperty("afterburner.farTerrain"))
			&& Stream.of("voxy", "distanthorizons", "sodium").noneMatch(FabricLoader.getInstance()::isModLoaded);

	private LabsFeatures() {
	}
}
