package com.afterburner.bench;

import com.afterburner.Afterburner;
import com.afterburner.Platform;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Afterburner Bench: the benchmark and check commands used while developing Afterburner (not released). The dev client
 * loads it next to Afterburner, as its own mod ({@code afterburner_bench}).
 */
@Mod("afterburner_bench")
public final class Bench {
	public Bench(FMLJavaModLoadingContext context) {
		TickRecorder.register();
		BenchPlayer.register();
		ChunkCommand.init(context.getModBusGroup());
		RegisterCommandsEvent.BUS.addListener(event -> {
			TickCommand.register(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection());
			ChunkCommand.register(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection());
			LightCommand.register(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection());
			BaseCommand.register(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection());
			ExplosionCommand.register(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection());
			FarLandCommand.register(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection());
		});
		// For checks that run by themselves: -Dafterburner.benchCommands=afterburner-explosions;afterburner-base
		// runs these as the server (no cheats needed), one after another, once the world is open.
		String commands = System.getProperty("afterburner.benchCommands");
		if (commands != null) {
			ServerStartedEvent.BUS.addListener(event -> {
				for (String command : commands.split(";")) {
					Afterburner.LOGGER.info("Bench command: {}", command.strip());
					event.getServer().getCommands().performPrefixedCommand(event.getServer().createCommandSourceStack(), command.strip());
				}
			});
		}
		if (Platform.isClient()) com.afterburner.bench.client.BenchClient.init();
		// -Dafterburner.mixinAudit=true: applies every mixin now (Mixin loads each target), so one launch shows all the ones that fail.
		if (Boolean.getBoolean("afterburner.mixinAudit")) {
			Afterburner.LOGGER.info("Mixin audit: applying every mixin");
			org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit();
			Afterburner.LOGGER.info("Mixin audit done");
		}
	}
}
