package com.afterburner.client;

import com.afterburner.Features;
import com.afterburner.client.memory.MemoryTrim;
import net.fabricmc.api.ClientModInitializer;

public class AfterburnerClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		if (Features.MEMORY_TRIM.enabled()) MemoryTrim.init();
	}
}
