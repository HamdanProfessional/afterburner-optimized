package com.afterburner.labs;

import com.afterburner.labs.lod.Lod;

/** The labs' client setup, from {@link com.afterburner.client.AfterburnerClient}. */
public final class LabsClient {
	private LabsClient() {
	}

	public static void init() {
		Lod.init();
	}
}
