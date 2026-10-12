package com.afterburner.client.mixin;

import com.afterburner.client.gui.SettingsScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Puts the button for Afterburner's settings ({@link SettingsScreen}) at the bottom of Video Settings. */
@Mixin(VideoSettingsScreen.class)
abstract class VideoSettingsMixin extends OptionsSubScreen {
	private VideoSettingsMixin(Screen lastScreen, Options options, Component title) {
		super(lastScreen, options, title);
	}

	@Inject(method = "addOptions", at = @At("TAIL"))
	private void afterburner$addSettingsButton(CallbackInfo ci) {
		if (list == null) return;
		list.addHeader(Component.translatable("afterburner.settings.title").withStyle(ChatFormatting.UNDERLINE, ChatFormatting.BOLD));
		list.addBig(Button.builder(Component.translatable("afterburner.settings.button"),
				b -> minecraft.gui.setScreen(new SettingsScreen(this, options))).build());
	}
}
