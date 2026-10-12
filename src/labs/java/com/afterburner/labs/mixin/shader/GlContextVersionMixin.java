package com.afterburner.labs.mixin.shader;

import com.afterburner.Mobile;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import java.util.Locale;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The game asks for an OpenGL 3.2 context, and some drivers (Intel's on Windows) give exactly that, though the GPU does 4.6:
 * then shader packs' images, storage buffers and compute programs (colored lighting) can't run. So the newest of 4.6, 4.5
 * and 4.3 is asked for first, and 3.2 as the game does if the driver gives none of them. A 4.x core context runs everything
 * a 3.2 one does. A version the driver doesn't have is an error the game would crash on, so its error callback is left out
 * while those are tried. {@code -Dafterburner.gl46=false} leaves it to the game; macOS (whose OpenGL stops at 4.1) and phones
 * (whose launcher turns OpenGL into OpenGL ES or Vulkan) are left alone.
 */
@Mixin(Window.class)
public class GlContextVersionMixin {
	@WrapOperation(method = "<init>", at = @At(value = "INVOKE", remap = false,
			target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
	private long afterburner$newerContext(int width, int height, CharSequence title, long monitor, long share, Operation<Long> original) {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("mac") || Mobile.ACTIVE || !Boolean.parseBoolean(System.getProperty("afterburner.gl46", "true"))) return original.call(width, height, title, monitor, share);
		int[][] versions = {{4, 6}, {4, 5}, {4, 3}};
		GLFWErrorCallback game = GLFW.glfwSetErrorCallback(null);
		try {
			for (int[] v : versions) {
				GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, v[0]);
				GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, v[1]);
				long window = original.call(width, height, title, monitor, share);
				if (window != 0L) {
					LoggerFactory.getLogger("afterburner").info("[Afterburner] OpenGL {}.{} context (the game asks for 3.2)", v[0], v[1]);
					return window;
				}
			}
		} finally {
			// What the game asked for, for its later windows and checks.
			GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
			GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
			GLFW.glfwSetErrorCallback(game);
		}
		return original.call(width, height, title, monitor, share);
	}
}
