package com.afterburner.client.gui;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * With Sodium installed, Sodium's own screen replaces Video Settings, so the Afterburner button ({@code VideoSettingsMixin})
 * isn't there. This puts an Afterburner page in Sodium's menu instead, which opens the same settings page. Sodium finds
 * it through the {@code sodium:config_api_user} entrypoint; without Sodium this class is never loaded.
 */
public final class SodiumSettingsPage implements ConfigEntryPoint {
	@Override
	public void registerConfigLate(ConfigBuilder builder) {
		builder.registerOwnModOptions().addPage(builder.createExternalPage()
				.setName(Component.translatable("afterburner.settings.title"))
				.setScreenConsumer(parent -> Minecraft.getInstance().gui.setScreen(new SettingsScreen(parent, Minecraft.getInstance().options))));
	}
}
