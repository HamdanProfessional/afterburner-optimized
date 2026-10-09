package com.afterburner.labs.mixin.lod;

import com.afterburner.labs.lod.Lod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The far terrain keeps the chunks still loaded when the level goes away (Fabric's unload event doesn't fire then). */
@Mixin(Minecraft.class)
public abstract class LodMinecraftMixin {
	@Shadow
	public @Nullable ClientLevel level;

	@Inject(method = "setLevel", at = @At("HEAD"))
	private void afterburner$changeLevel(ClientLevel next, CallbackInfo ci) {
		if (this.level != null && this.level != next) Lod.leave(this.level);
	}

	@Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
	private void afterburner$disconnect(Screen screen, boolean keepResourcePacks, boolean stopSound, CallbackInfo ci) {
		if (this.level != null) Lod.leave(this.level);
	}

	@Inject(method = "clearClientLevel", at = @At("HEAD"))
	private void afterburner$clearLevel(Screen screen, CallbackInfo ci) {
		if (this.level != null) Lod.leave(this.level);
	}
}
