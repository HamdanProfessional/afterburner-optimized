package com.afterburner;

import net.minecraft.resources.Identifier;
import net.minecraftforge.fml.common.Mod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Afterburner.MOD_ID)
public final class Afterburner {
	public static final String MOD_ID = "afterburner";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public Afterburner() {
		LOGGER.info(Mobile.ACTIVE ? "Afterburner loaded (phone or tablet: mobile defaults)" : "Afterburner loaded");
		// Client-only setup. Only reached on the client, so a dedicated server never loads the client classes.
		if (Platform.isClient()) com.afterburner.client.AfterburnerClient.init();
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
