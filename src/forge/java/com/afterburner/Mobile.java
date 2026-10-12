package com.afterburner;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether the game runs on a phone or tablet, through an Android launcher (PojavLauncher, Zalith, FCL and the like), where
 * some settings start lower: phone GPUs draw the screen in tiles, and memory is short. Only the starting values change; what
 * the player sets is kept. {@code -Dafterburner.mobile=true} or {@code false} decides it by hand.
 */
public final class Mobile {
	public static final boolean ACTIVE = detect();

	private Mobile() {
	}

	private static boolean detect() {
		String flag = System.getProperty("afterburner.mobile");
		if (flag != null) return !"false".equalsIgnoreCase(flag.strip());
		// The launcher runs Java inside its Android app, which passes Android's environment along.
		for (String name : new String[] {"ANDROID_ROOT", "ANDROID_DATA", "POJAV_RENDERER", "POJAV_NATIVEDIR"}) {
			if (System.getenv(name) != null) return true;
		}
		try {
			return Files.exists(Path.of("/system/build.prop"));
		} catch (RuntimeException e) {
			return false;
		}
	}
}
