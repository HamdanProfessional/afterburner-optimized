package com.afterburner.client;

import com.afterburner.Features;
import com.afterburner.client.memory.MemoryTrim;
import com.afterburner.labs.LabsClient;

/** The client's setup, called once from Afterburner's constructor when the game runs on the client. */
public final class AfterburnerClient {
	private AfterburnerClient() {
	}

	public static void init() {
		if (Features.MEMORY_TRIM.enabled()) MemoryTrim.init();
		LabsClient.init();
	}
}
