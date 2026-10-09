package com.afterburner.labs.render;

import com.afterburner.labs.mixin.render.RenderTargetAccessor;
import com.afterburner.labs.shaderpack.game.Shaderpacks;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * FSR 1 upscaling for the game's own drawing. While the world is drawn, the main target's textures (and the hand's depth
 * target's) are swapped for smaller ones, so everything that draws the world (terrain, entities, particles, the sky,
 * clouds, the hand) fills fewer pixels; then the smaller picture is scaled up into the main target with {@link Upscaler}, and
 * the menus and hotbar are drawn over it at full size, sharp.
 * <p>
 * A shader pack does this itself, over all of its passes (see PackRenderer), so this stays out while one is drawing.
 */
public final class WorldScale {
	/** The game's texture uses (copy to and from, read, draw into). */
	private static final int USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING
		| GpuTexture.USAGE_RENDER_ATTACHMENT;
	private static final Vector4f CLEAR = new Vector4f(0.0F);

	private static final Swap MAIN = new Swap("Afterburner scaled world");
	private static final Swap HAND_DEPTH = new Swap("Afterburner scaled hand depth");
	private static @Nullable Upscaler upscaler;
	private static boolean failed;

	private WorldScale() {
	}

	/** Before the world is drawn: whether it's drawn smaller this frame (then {@link #end} must follow). */
	public static boolean begin(RenderTarget main, RenderTarget handDepth) {
		float scale = Shaderpacks.upscale().scale;
		if (scale >= 1.0F) {
			MAIN.release();
			HAND_DEPTH.release();
			return false;
		}
		if (failed || main.width <= 0 || main.height <= 0) return false;
		Minecraft mc = Minecraft.getInstance();
		if (Shaderpacks.drawsWorld(mc)) return false;
		Upscaler u = upscaler(main);
		if (u == null) return false;
		int width = Math.max(1, Math.round(main.width * scale));
		int height = Math.max(1, Math.round(main.height * scale));
		MAIN.swapIn(main, width, height);
		HAND_DEPTH.swapIn(handDepth, width, height);
		// As the game clears the main target before the world.
		GpuTexture color = main.getColorTexture();
		GpuTexture depth = main.getDepthTexture();
		if (color != null && depth != null) RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(color, CLEAR, depth, 0.0);
		return true;
	}

	/** After the world: the targets get their own textures back, and the world is scaled up into the main one. */
	public static void end(RenderTarget main, RenderTarget handDepth) {
		GpuTextureView picture = MAIN.swapOut(main);
		HAND_DEPTH.swapOut(handDepth);
		GpuTextureView screen = main.getColorTextureView();
		if (picture != null && screen != null && upscaler != null) upscaler.run(picture, screen);
	}

	private static @Nullable Upscaler upscaler(RenderTarget main) {
		if (upscaler == null) {
			GpuTexture color = main.getColorTexture();
			if (color == null) return null;
			Upscaler u = new Upscaler(color.getFormat());
			if (!u.works()) {
				u.close();
				failed = true;
				return null;
			}
			upscaler = u;
		}
		return upscaler;
	}

	/** Smaller textures put in a render target's place, and the target's own kept meanwhile. */
	private static final class Swap {
		private final String label;
		private @Nullable GpuTexture color, depth;
		private @Nullable GpuTextureView colorView, depthView;
		private @Nullable GpuTexture savedColor, savedDepth;
		private @Nullable GpuTextureView savedColorView, savedDepthView;
		private int savedWidth, savedHeight;
		private boolean in;

		Swap(String label) {
			this.label = label;
		}

		void swapIn(RenderTarget target, int width, int height) {
			RenderTargetAccessor t = (RenderTargetAccessor) target;
			this.ensure(t.afterburner$colorFormat(), t.afterburner$depthFormat(), width, height);
			this.savedColor = t.afterburner$colorTexture();
			this.savedColorView = t.afterburner$colorTextureView();
			this.savedDepth = t.afterburner$depthTexture();
			this.savedDepthView = t.afterburner$depthTextureView();
			this.savedWidth = target.width;
			this.savedHeight = target.height;
			t.afterburner$setColorTexture(this.color);
			t.afterburner$setColorTextureView(this.colorView);
			t.afterburner$setDepthTexture(this.depth);
			t.afterburner$setDepthTextureView(this.depthView);
			target.width = width;
			target.height = height;
			this.in = true;
		}

		/** The smaller color texture the world was drawn into. */
		@Nullable GpuTextureView swapOut(RenderTarget target) {
			if (!this.in) return null;
			this.in = false;
			RenderTargetAccessor t = (RenderTargetAccessor) target;
			t.afterburner$setColorTexture(this.savedColor);
			t.afterburner$setColorTextureView(this.savedColorView);
			t.afterburner$setDepthTexture(this.savedDepth);
			t.afterburner$setDepthTextureView(this.savedDepthView);
			target.width = this.savedWidth;
			target.height = this.savedHeight;
			this.savedColor = this.savedDepth = null;
			this.savedColorView = this.savedDepthView = null;
			return this.colorView;
		}

		/** Lets the smaller textures go while upscaling is off. */
		void release() {
			if (this.in) return;
			this.ensure(null, null, 0, 0);
		}

		/** Textures of the size and the target's formats, made again when either changes. */
		private void ensure(@Nullable GpuFormat colorFormat, @Nullable GpuFormat depthFormat, int width, int height) {
			if (!fits(this.color, colorFormat, width, height)) {
				if (this.colorView != null) this.colorView.close();
				if (this.color != null) this.color.close();
				this.color = null;
				this.colorView = null;
				if (colorFormat != null) {
					GpuDevice device = RenderSystem.getDevice();
					this.color = device.createTexture(this.label + " / Color", USAGE, colorFormat, width, height, 1, 1);
					this.colorView = device.createTextureView(this.color);
				}
			}
			if (!fits(this.depth, depthFormat, width, height)) {
				if (this.depthView != null) this.depthView.close();
				if (this.depth != null) this.depth.close();
				this.depth = null;
				this.depthView = null;
				if (depthFormat != null) {
					GpuDevice device = RenderSystem.getDevice();
					this.depth = device.createTexture(this.label + " / Depth", USAGE, depthFormat, width, height, 1, 1);
					this.depthView = device.createTextureView(this.depth);
				}
			}
		}

		private static boolean fits(@Nullable GpuTexture texture, @Nullable GpuFormat format, int width, int height) {
			if (texture == null) return format == null;
			return texture.getFormat() == format && texture.getWidth(0) == width && texture.getHeight(0) == height;
		}
	}
}
