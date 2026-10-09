package com.afterburner.bench.client;

import com.afterburner.client.render.Probe;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

public class BenchClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register(BenchCommand::register);
		AutoBench.init();
		Probe.set(new BenchProbe());
	}
}
