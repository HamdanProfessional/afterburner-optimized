package com.afterburner.bench;

import com.afterburner.Afterburner;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

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
		CommandRegistrationCallback.EVENT.register(ExplosionCommand::register);
		// For checks that run by themselves: -Dafterburner.benchCommands=afterburner-explosions;afterburner-base
		// runs these as the server (no cheats needed), one after another, once the world is open.
		String commands = System.getProperty("afterburner.benchCommands");
		if (commands != null) {
			ServerLifecycleEvents.SERVER_STARTED.register(server -> {
				for (String command : commands.split(";")) {
					Afterburner.LOGGER.info("Bench command: {}", command.strip());
					server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command.strip());
				}
			});
		}
	}
}
