package com.afterburner.labs;

import com.afterburner.labs.lod.Lod;
import net.fabricmc.api.ClientModInitializer;

public class LabsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Lod.init();
	}
}
