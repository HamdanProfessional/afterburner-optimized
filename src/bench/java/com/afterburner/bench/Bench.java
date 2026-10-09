package com.afterburner.bench;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

/**
 * Afterburner Bench: the benchmark and check commands used while developing Afterburner (not released). The dev client
 * loads it next to Afterburner; the build also makes it a jar of its own (build/bench), for test servers.
 */
public class Bench implements ModInitializer {
	@Override
	public void onInitialize() {
		TickRecorder.register();
		BenchPlayer.register();
		ChunkCommand.init();
		CommandRegistrationCallback.EVENT.register(TickCommand::register);
		CommandRegistrationCallback.EVENT.register(ChunkCommand::register);
		CommandRegistrationCallback.EVENT.register(LightCommand::register);
		CommandRegistrationCallback.EVENT.register(BaseCommand::register);
	}
}
