package com.afterburner;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * What Afterburner needs from the loader: the game's folders, whether a mod is installed, which side the game runs on,
 * and the addons listed in META-INF/services. Everything loader-specific goes through here, so the rest of the mod
 * doesn't depend on Forge directly.
 */
public final class Platform {
	private Platform() {
	}

	/** The config folder (the game's {@code config} directory). */
	public static Path configDir() {
		return FMLPaths.CONFIGDIR.get();
	}

	/** The game's folder (where {@code mods}, {@code saves} and the like are). */
	public static Path gameDir() {
		return FMLPaths.GAMEDIR.get();
	}

	/**
	 * Whether a mod with this id is installed. Works while mixins are being set up, and later. If Forge stopped loading mods
	 * (another mod's dependency is missing), there is no list and nothing counts as installed: Forge's own error screen is
	 * then shown, not an Afterburner crash.
	 */
	public static boolean isModLoaded(String modId) {
		return modFile(modId) != null;
	}

	/** The display name of an installed mod, or null if it isn't installed. */
	public static String modName(String modId) {
		ModFileInfo file = modFile(modId);
		if (file == null) return null;
		for (IModInfo mod : file.getMods()) {
			if (mod.getModId().equals(modId)) return mod.getDisplayName();
		}
		return modId;
	}

	private static ModFileInfo modFile(String modId) {
		try {
			return LoadingModList.getModFileById(modId);
		} catch (RuntimeException e) {
			// No list yet, or Forge gave up loading mods.
			return null;
		}
	}

	/** Whether the game runs on the client (not a dedicated server). */
	public static boolean isClient() {
		return FMLEnvironment.dist == Dist.CLIENT;
	}

	/** The implementations of an addon interface, found through META-INF/services. */
	public static <T> List<T> services(Class<T> type) {
		List<T> found = new ArrayList<>();
		for (T service : ServiceLoader.load(type, Platform.class.getClassLoader())) found.add(service);
		return found;
	}
}
