package com.afterburner.labs.mixin.shader;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Locale;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The game asks for an OpenGL 3.3 context, and some drivers (Intel's on Windows) give exactly that, though the GPU does 4.6:
 * then shader packs' images, storage buffers and compute programs (colored lighting) can't run. So the newest of 4.6, 4.5
 * and 4.3 is asked for first, and 3.3 as the game does if the driver gives none of them. A 4.x core context runs everything
 * a 3.3 one does. {@code -Dafterburner.gl33=true} keeps the game's 3.3; macOS (whose OpenGL stops at 4.1) is left alone.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlDevice")
public class GlContextVersionMixin {
	@WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_CreateContext(J)J"))
	private long afterburner$newerContext(long window, Operation<Long> original) {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("mac") || Boolean.getBoolean("afterburner.gl33")) return original.call(window);
		int[][] versions = {{4, 6}, {4, 5}, {4, 3}};
		try {
			for (int[] v : versions) {
				SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, v[0]);
				SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION, v[1]);
				long context = original.call(window);
				if (context != 0L) {
					LoggerFactory.getLogger("afterburner").info("[Afterburner] OpenGL {}.{} context (the game asks for 3.3)", v[0], v[1]);
					return context;
				}
			}
		} finally {
			// What the game asked for, for its later windows and checks.
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, 3);
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION, 3);
		}
		return original.call(window);
	}
}
