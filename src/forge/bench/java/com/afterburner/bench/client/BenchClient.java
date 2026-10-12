package com.afterburner.bench.client;

import com.afterburner.client.render.Probe;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;

/** The bench's client setup, called from {@link com.afterburner.bench.Bench} on the client. */
public final class BenchClient {
	private BenchClient() {
	}

	public static void init() {
		RegisterClientCommandsEvent.BUS.addListener(event -> BenchCommand.register(event.getDispatcher(), event.getBuildContext()));
		AutoBench.init();
		Probe.set(new BenchProbe());
	}
}
