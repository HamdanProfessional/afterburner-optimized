package com.afterburner.labs;

import com.afterburner.labs.worldgen.FarLandCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

/**
 * Afterburner Labs: what we're still working on, kept out of the released mod until it works and has been tested (the shader
 * pack loader, upscaling, far terrain). The dev client loads it next to Afterburner, which it plugs into through
 * {@link LabsAddon}. The build also makes it a jar of its own (build/labs), for test servers.
 */
public class Labs implements ModInitializer {
	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register(FarLandCommand::register);
	}
}
