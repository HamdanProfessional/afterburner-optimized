package com.afterburner.labs.render;

import com.afterburner.labs.mixin.render.RenderTargetAccessor;
import com.afterburner.labs.mixin.shader.LevelRendererAccessor;
import com.afterburner.labs.shaderpack.game.Gl;
import com.afterburner.labs.shaderpack.game.Shaderpacks;
import com.afterburner.labs.shaderpack.game.Upscaler;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * FSR 1 upscaling for the game's own drawing. While the world is drawn, the main target's framebuffer, textures and size are
 * swapped for smaller ones, so everything the world's drawing fills (terrain, entities, particles, the sky, clouds, the hand)
 * fills fewer pixels; then the smaller picture is scaled up into the main target with {@link Upscaler}, and the menus and
 * hotbar are drawn over it at full size, sharp.
 * <p>
 * A shader pack does this itself, over all of its passes (see PackRenderer), so this stays out while one is drawing. The
 * game's own full-size depth copies (Fabulous graphics, glowing entities) aren't made while it's smaller, so upscaling stays
 * out then too.
 */
public final class WorldScale {
	private static final Swap MAIN = new Swap();

	private static @Nullable Upscaler upscaler;
	private static boolean failed;

	private WorldScale() {
	}

	/** Before the world is drawn: whether it's drawn smaller this frame (then {@link #end} must follow). */
	public static boolean begin(RenderTarget main) {
		float scale = Shaderpacks.upscale().scale;
		if (scale >= 1.0F) {
			MAIN.release();
			return false;
		}
		if (failed || main.width <= 0 || main.height <= 0) return false;
		Minecraft mc = Minecraft.getInstance();
		if (Shaderpacks.drawsWorld(mc)) return false;
		// Fabulous graphics and glowing entities copy the main target's depth at full size, which a smaller one can't give.
		if (Minecraft.useShaderTransparency() || ((LevelRendererAccessor) mc.levelRenderer).afterburner$showsEntityOutlines()) {
			MAIN.release();
			return false;
		}
		Upscaler u = upscaler();
		if (u == null) return false;
		int width = Math.max(1, Math.round(main.width * scale));
		int height = Math.max(1, Math.round(main.height * scale));
		MAIN.swapIn(main, width, height);
		return true;
	}

	/** After the world: the target gets its own framebuffer and textures back, and the world is scaled up into the main one. */
	public static void end(RenderTarget main) {
		if (!MAIN.swapOut(main)) return;
		Gl.bindFramebuffer(main.frameBufferId);
		Gl.viewport(main.viewWidth, main.viewHeight);
		Upscaler u = upscaler;
		if (u != null) u.run(MAIN.color, MAIN.width, MAIN.height, main.frameBufferId, main.width, main.height);
		// Depth written again: left off, the next frame's clear keeps the old depth and its terrain doesn't show.
		Gl.baseline();
	}

	private static @Nullable Upscaler upscaler() {
		if (upscaler == null && !failed) {
			Upscaler u = new Upscaler();
			if (u.works()) {
				upscaler = u;
			} else {
				u.close();
				failed = true;
			}
		}
		return upscaler;
	}

	/** Smaller textures and a framebuffer put in the main target's place, and the target's own kept meanwhile. */
	private static final class Swap {
		private int color, depth, framebuffer;
		private int savedFramebuffer, savedColor, savedDepth;
		private int savedWidth, savedHeight, savedViewWidth, savedViewHeight;
		private int width, height;
		private boolean in;

		void swapIn(RenderTarget target, int width, int height) {
			this.ensure(width, height);
			RenderTargetAccessor t = (RenderTargetAccessor) target;
			this.savedFramebuffer = target.frameBufferId;
			this.savedColor = t.afterburner$colorTextureId();
			this.savedDepth = t.afterburner$depthBufferId();
			this.savedWidth = target.width;
			this.savedHeight = target.height;
			this.savedViewWidth = target.viewWidth;
			this.savedViewHeight = target.viewHeight;
			target.frameBufferId = this.framebuffer;
			t.afterburner$setColorTextureId(this.color);
			t.afterburner$setDepthBufferId(this.depth);
			target.width = width;
			target.height = height;
			target.viewWidth = width;
			target.viewHeight = height;
			this.in = true;
			Gl.bindFramebuffer(this.framebuffer);
			Gl.viewport(width, height);
		}

		/** Puts the target's own framebuffer and textures back; whether the smaller picture is ready to scale up. */
		boolean swapOut(RenderTarget target) {
			if (!this.in) return false;
			this.in = false;
			RenderTargetAccessor t = (RenderTargetAccessor) target;
			target.frameBufferId = this.savedFramebuffer;
			t.afterburner$setColorTextureId(this.savedColor);
			t.afterburner$setDepthBufferId(this.savedDepth);
			target.width = this.savedWidth;
			target.height = this.savedHeight;
			target.viewWidth = this.savedViewWidth;
			target.viewHeight = this.savedViewHeight;
			return true;
		}

		/** Lets the smaller textures go while upscaling is off. */
		void release() {
			if (this.in || this.framebuffer == 0) return;
			Gl.deleteTexture(this.color);
			Gl.deleteTexture(this.depth);
			Gl.deleteFramebuffer(this.framebuffer);
			this.color = this.depth = this.framebuffer = 0;
			this.width = this.height = 0;
		}

		/** Textures and a framebuffer of the drawn size, made again when the size changes. */
		private void ensure(int width, int height) {
			if (this.framebuffer != 0 && width == this.width && height == this.height) return;
			this.release();
			this.color = Gl.colorTexture(width, height);
			this.depth = Gl.depthTexture(width, height);
			this.framebuffer = Gl.framebuffer(this.color, this.depth);
			this.width = width;
			this.height = height;
		}
	}
}
