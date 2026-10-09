package com.afterburner.labs;

import com.afterburner.labs.worldgen.FarLandCommand;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

/**
 * Afterburner Labs: the newer, experimental parts (the shader pack loader, upscaling, far terrain), a mod of its own that
 * plugs into Afterburner through {@link LabsAddon}. The released jar carries it inside; the build also makes it a jar of its
 * own (build/labs).
 */
public class Labs implements ModInitializer {
	@Override
	public void onInitialize() {
		// A check that uses the bench tools' chunk loading: only where they're installed (the dev client).
		if (FabricLoader.getInstance().isModLoaded("afterburner_bench")) CommandRegistrationCallback.EVENT.register(FarLandCommand::register);
	}
}
