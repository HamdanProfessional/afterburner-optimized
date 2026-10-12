package com.afterburner.labs.mixin.shader;

import com.afterburner.labs.shaderpack.game.ProgramLinker;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDL_Event;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The game takes every window event as its window's (a resize, a move, a close): the shader linker's hidden window's are
 * dropped (see {@link ProgramLinker}).
 */
@Mixin(Window.class)
public class LinkerWindowEventsMixin {
	@Inject(method = "handleEvent", at = @At("HEAD"), cancellable = true)
	private void afterburner$notTheGames(SDL_Event event, CallbackInfo ci) {
		int type = event.type();
		boolean windowEvent = type >= SDLEvents.SDL_EVENT_WINDOW_FIRST && type <= SDLEvents.SDL_EVENT_WINDOW_LAST;
		if (windowEvent && ProgramLinker.isLinkerWindow(event.window().windowID())) ci.cancel();
	}
}
