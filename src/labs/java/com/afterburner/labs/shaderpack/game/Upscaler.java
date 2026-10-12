package com.afterburner.labs.shaderpack.game;

import com.afterburner.labs.shaderpack.UpscaleShaders;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

/**
 * AMD FidelityFX Super Resolution 1 (FSR 1), written after AMD's published algorithm (MIT licensed): the world is drawn
 * smaller, EASU scales the picture up to the screen, smoothing along the edges it finds instead of across them, and RCAS
 * sharpens the result without letting any pixel overshoot its neighbors. Used for the game's own drawing (WorldScale) and
 * for a shader pack's.
 */
public final class Upscaler implements AutoCloseable {
	/** RCAS's default sharpness, in stops (0 is the sharpest). */
	private static final float SHARPNESS = 0.2F;
	private static final String[] NO_ATTRIBUTES = new String[0];

	private final Gl.@Nullable Program easu;
	private final Gl.@Nullable Program rcas;
	private final int constants;
	private final int sampler;
	private final ByteBuffer data = MemoryUtil.memAlloc(16);
	private final Gl.Framebuffer inputFramebuffer, upscaledFramebuffer;
	private int input, upscaled;
	private int inWidth, inHeight, outWidth, outHeight;

	public Upscaler() {
		int bound = Gl.drawFramebuffer();
		this.easu = compile("easu", UpscaleShaders.EASU);
		this.rcas = compile("rcas", UpscaleShaders.RCAS);
		this.constants = Gl.uniformBuffer(16);
		// The picture is read texel by texel, whatever its texture's own filtering.
		this.sampler = GL33C.glGenSamplers();
		GL33C.glSamplerParameteri(this.sampler, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
		GL33C.glSamplerParameteri(this.sampler, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
		GL33C.glSamplerParameteri(this.sampler, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
		GL33C.glSamplerParameteri(this.sampler, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
		this.inputFramebuffer = new Gl.Framebuffer();
		this.upscaledFramebuffer = new Gl.Framebuffer();
		Gl.bindFramebuffer(bound);
	}

	private static Gl.@Nullable Program compile(String name, String fragment) {
		Gl.Program p = Gl.program("Afterburner FSR 1 " + name, UpscaleShaders.VERTEX, fragment, NO_ATTRIBUTES, l -> name + ".vsh:" + l,
			l -> name + ".fsh:" + l, linked -> {
				linked.uniform(UpscaleShaders.SAMPLER, Gl.FIRST_UNIT);
				linked.block(UpscaleShaders.BLOCK, Gl.UPSCALE_BINDING);
			});
		if (p.ready()) return p;
		LoggerFactory.getLogger("afterburner").warn("[Afterburner] FSR 1 {} didn't compile: {}", name, p.error());
		p.close();
		return null;
	}

	/** Whether both programs compiled. */
	public boolean works() {
		return this.easu != null && this.rcas != null;
	}

	/**
	 * A framebuffer with a texture of the drawn size, kept: what a shader pack's final pass draws into instead of the screen
	 * (then {@link #inputTexture} is the picture).
	 */
	int inputFramebuffer(int width, int height) {
		if (this.input == 0 || width != this.inWidth || height != this.inHeight) {
			Gl.deleteTexture(this.input);
			this.input = Gl.texture(Gl.Format.RGBA8, width, height, true, false);
			this.inWidth = width;
			this.inHeight = height;
			int bound = Gl.drawFramebuffer();
			this.inputFramebuffer.bind().color(0, this.input).drawBuffers(Gl.all(1));
			Gl.bindFramebuffer(bound);
		}
		return this.inputFramebuffer.id;
	}

	int inputTexture() {
		return this.input;
	}

	/**
	 * Scales texture {@code source} ({@code sourceWidth} by {@code sourceHeight}, smaller than the screen) up into framebuffer
	 * {@code screen} ({@code width} by {@code height}), then sharpens it. Draws with the fullscreen state ({@link Gl#fullscreenState});
	 * leaves {@code screen} bound.
	 */
	public void run(int source, int sourceWidth, int sourceHeight, int screen, int width, int height) {
		if (this.easu == null || this.rcas == null) return;
		if (this.upscaled == 0 || width != this.outWidth || height != this.outHeight) {
			Gl.deleteTexture(this.upscaled);
			this.upscaled = Gl.texture(Gl.Format.RGBA8, width, height, true, false);
			this.outWidth = width;
			this.outHeight = height;
			this.upscaledFramebuffer.bind().color(0, this.upscaled).drawBuffers(Gl.all(1));
		}
		this.data.putFloat(0, width).putFloat(4, height).putFloat(8, (float) Math.pow(2.0, -SHARPNESS)).putFloat(12, 0.0F);
		this.data.position(0).limit(16);
		Gl.upload(this.constants, this.data);
		Gl.fullscreenState();
		Gl.bindUniformBuffer(Gl.UPSCALE_BINDING, this.constants);
		GL33C.glBindSampler(Gl.FIRST_UNIT, this.sampler);

		this.upscaledFramebuffer.bind();
		Gl.viewport(width, height);
		this.pass(this.easu, source);
		Gl.bindFramebuffer(screen);
		this.pass(this.rcas, this.upscaled);

		GL33C.glBindSampler(Gl.FIRST_UNIT, 0);
		Gl.restoreActiveTexture();
	}

	private void pass(Gl.Program program, int source) {
		Gl.useProgram(program.id);
		Gl.bindUnit(Gl.FIRST_UNIT, GL11C.GL_TEXTURE_2D, source);
		Gl.drawFullscreen();
	}

	@Override
	public void close() {
		Gl.deleteTexture(this.input);
		Gl.deleteTexture(this.upscaled);
		this.input = this.upscaled = 0;
		this.inputFramebuffer.close();
		this.upscaledFramebuffer.close();
		if (this.easu != null) this.easu.close();
		if (this.rcas != null) this.rcas.close();
		Gl.deleteBuffer(this.constants);
		GL33C.glDeleteSamplers(this.sampler);
		MemoryUtil.memFree(this.data);
	}
}
