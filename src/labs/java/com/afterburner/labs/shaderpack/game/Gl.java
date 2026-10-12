package com.afterburner.labs.shaderpack.game;

import com.afterburner.labs.shaderpack.PackBlending;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.BufferUploader;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.ARBParallelShaderCompile;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.KHRParallelShaderCompile;
import org.lwjgl.system.MemoryStack;

/**
 * OpenGL for the shader packs, next to the game's own tracking of it (GlStateManager): texture units past the game's twelve,
 * framebuffers, programs and uniform buffers the game doesn't know about, and what the game set for blending, so a pack's
 * blend can be put back. Render thread only.
 */
public final class Gl {
	/** The unit textures are made and filled on (the game tracks units 0-11). */
	static final int SETUP_UNIT = 12;
	/** The first unit the pack's samplers are on. */
	static final int FIRST_UNIT = 13;
	/** Uniform buffer bindings: the pack's frame block and draw block (UniformLayout), the upscaler's. */
	static final int FRAME_BINDING = 0, DRAW_BINDING = 1, UPSCALE_BINDING = 2;
	private static final int UNIFORM_BINDINGS = 8;
	/** Where the pack's program counts as finished compiling (KHR_parallel_shader_compile). */
	private static final int COMPLETION_STATUS = 0x91B1;
	/** A line of a driver's compile log that names a line of the source: "0(12)" (NVIDIA), "0:12" (AMD, Intel, Mesa). */
	private static final Pattern LOG_LINE = Pattern.compile("(?<![\\w.])0[:(](\\d+)\\)?");

	private static int units = -1;
	private static int[] unitTargets = new int[0];
	private static int[] unitTextures = new int[0];
	/** The unit active in OpenGL when we changed it ({@link #restoreActiveTexture}), -1 when it's the game's. */
	private static int rawActive = -1;
	private static final int[] uniformBuffers = new int[UNIFORM_BINDINGS];
	private static int drawFramebuffer;
	private static int emptyArray;
	private static int scratchRead, scratchDraw;
	private static @Nullable Boolean clearTexture, copyImage, parallel;

	private Gl() {
	}

	private static GLCapabilities caps() {
		return GL.getCapabilities();
	}

	/** Whether textures can be cleared without a framebuffer (OpenGL 4.4, or ARB_clear_texture). */
	static boolean clearsTextures() {
		if (clearTexture == null) clearTexture = caps().OpenGL44 || caps().GL_ARB_clear_texture;
		return clearTexture;
	}

	/** Whether textures can be copied without a framebuffer (OpenGL 4.3, or ARB_copy_image). */
	static boolean copiesImages() {
		if (copyImage == null) copyImage = caps().OpenGL43 || caps().GL_ARB_copy_image;
		return copyImage;
	}

	/** Whether programs compile in the background (KHR_ or ARB_parallel_shader_compile). */
	static boolean compilesInParallel() {
		if (parallel == null) {
			GLCapabilities caps = caps();
			if (caps.GL_KHR_parallel_shader_compile) {
				KHRParallelShaderCompile.glMaxShaderCompilerThreadsKHR(0xFFFFFFFF);
				parallel = true;
			} else if (caps.GL_ARB_parallel_shader_compile) {
				ARBParallelShaderCompile.glMaxShaderCompilerThreadsARB(0xFFFFFFFF);
				parallel = true;
			} else {
				parallel = false;
			}
		}
		return parallel;
	}

	// ---- Texture units ----

	/** How many texture units there are, all stages together. */
	static int units() {
		if (units < 0) {
			units = GL11C.glGetInteger(GL20C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
			unitTargets = new int[units];
			unitTextures = new int[units];
			Arrays.fill(unitTextures, -1);
		}
		return units;
	}

	/**
	 * Binds a texture to one of our units (from {@link #SETUP_UNIT} on), if it isn't already. The active unit is left changed
	 * until {@link #restoreActiveTexture}.
	 */
	static void bindUnit(int unit, int target, int texture) {
		if (unit >= units() || unit < SETUP_UNIT) return;
		if (unitTextures[unit] == texture && unitTargets[unit] == target) return;
		if (rawActive != unit) {
			GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
			rawActive = unit;
		}
		GL11C.glBindTexture(target, texture);
		unitTargets[unit] = target;
		unitTextures[unit] = texture;
	}

	/** Makes the game's active unit active again after {@link #bindUnit}. */
	static void restoreActiveTexture() {
		if (rawActive < 0) return;
		GL13C.glActiveTexture(GlStateManager._getActiveTexture());
		rawActive = -1;
	}

	/** Forgets what's bound to our units and uniform buffer bindings: once a frame, in case something else changed them. */
	static void forgetBindings() {
		units();
		Arrays.fill(unitTextures, -1);
		Arrays.fill(uniformBuffers, -1);
	}

	/** Binds a texture on {@link #SETUP_UNIT} to set it up (always: a new texture may have the name of a deleted one). */
	private static void setup(int target, int texture) {
		units();
		unitTextures[SETUP_UNIT] = -1;
		bindUnit(SETUP_UNIT, target, texture);
		if (rawActive != SETUP_UNIT) {
			GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + SETUP_UNIT);
			rawActive = SETUP_UNIT;
		}
	}

	// ---- Formats and textures ----

	/** A texture format: how it's stored, and how it's given as data. */
	record Format(int internal, int format, int type, Kind kind, int components) {
		enum Kind {
			/** Normalized or floating point. */
			FLOAT,
			INT,
			UINT,
			DEPTH
		}

		static final Format RGBA8 = new Format(GL11C.GL_RGBA8, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, Kind.FLOAT, 4);
		static final Format DEPTH = new Format(GL30C.GL_DEPTH_COMPONENT32F, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, Kind.DEPTH, 1);

		boolean integer() {
			return this.kind == Kind.INT || this.kind == Kind.UINT;
		}

		boolean depth() {
			return this.kind == Kind.DEPTH;
		}
	}

	private static Format floats(int internal, int components) {
		return new Format(internal, layout(components, false), GL11C.GL_FLOAT, Format.Kind.FLOAT, components);
	}

	private static Format ints(int internal, int components, boolean unsigned) {
		return new Format(internal, layout(components, true), unsigned ? GL11C.GL_UNSIGNED_INT : GL11C.GL_INT,
			unsigned ? Format.Kind.UINT : Format.Kind.INT, components);
	}

	private static int layout(int components, boolean integer) {
		return switch (components) {
			case 1 -> integer ? GL30C.GL_RED_INTEGER : GL11C.GL_RED;
			case 2 -> integer ? GL30C.GL_RG_INTEGER : GL30C.GL_RG;
			case 3 -> integer ? GL30C.GL_RGB_INTEGER : GL11C.GL_RGB;
			default -> integer ? GL30C.GL_RGBA_INTEGER : GL11C.GL_RGBA;
		};
	}

	/**
	 * An OptiFine format name (colortex0Format = RGBA16F) as OpenGL's. Three-channel ones get four, which can always be drawn
	 * into (R11F_G11F_B10F can); RGB9_E5 can't be drawn into, so it's RGBA16F.
	 */
	static Format format(String name) {
		return switch (name.strip().toUpperCase(Locale.ROOT)) {
			case "R8" -> floats(GL30C.GL_R8, 1);
			case "RG8" -> floats(GL30C.GL_RG8, 2);
			case "R8_SNORM" -> floats(GL31C.GL_R8_SNORM, 1);
			case "RG8_SNORM" -> floats(GL31C.GL_RG8_SNORM, 2);
			case "RGB8_SNORM", "RGBA8_SNORM" -> floats(GL31C.GL_RGBA8_SNORM, 4);
			case "R16" -> floats(GL30C.GL_R16, 1);
			case "RG16" -> floats(GL30C.GL_RG16, 2);
			case "RGB16", "RGBA16" -> floats(GL11C.GL_RGBA16, 4);
			case "R16_SNORM" -> floats(GL31C.GL_R16_SNORM, 1);
			case "RG16_SNORM" -> floats(GL31C.GL_RG16_SNORM, 2);
			case "RGB16_SNORM", "RGBA16_SNORM" -> floats(GL31C.GL_RGBA16_SNORM, 4);
			case "R16F" -> floats(GL30C.GL_R16F, 1);
			case "RG16F" -> floats(GL30C.GL_RG16F, 2);
			case "RGB16F", "RGBA16F", "RGB9_E5" -> floats(GL30C.GL_RGBA16F, 4);
			case "R32F" -> floats(GL30C.GL_R32F, 1);
			case "RG32F" -> floats(GL30C.GL_RG32F, 2);
			case "RGB32F", "RGBA32F" -> floats(GL30C.GL_RGBA32F, 4);
			case "R11F_G11F_B10F" -> floats(GL30C.GL_R11F_G11F_B10F, 3);
			case "RGB10_A2" -> floats(GL11C.GL_RGB10_A2, 4);
			case "R8I" -> ints(GL30C.GL_R8I, 1, false);
			case "RG8I" -> ints(GL30C.GL_RG8I, 2, false);
			case "RGB8I", "RGBA8I" -> ints(GL30C.GL_RGBA8I, 4, false);
			case "R8UI" -> ints(GL30C.GL_R8UI, 1, true);
			case "RG8UI" -> ints(GL30C.GL_RG8UI, 2, true);
			case "RGB8UI", "RGBA8UI" -> ints(GL30C.GL_RGBA8UI, 4, true);
			case "R16I" -> ints(GL30C.GL_R16I, 1, false);
			case "RG16I" -> ints(GL30C.GL_RG16I, 2, false);
			case "RGB16I", "RGBA16I" -> ints(GL30C.GL_RGBA16I, 4, false);
			case "R16UI" -> ints(GL30C.GL_R16UI, 1, true);
			case "RG16UI" -> ints(GL30C.GL_RG16UI, 2, true);
			case "RGB16UI", "RGBA16UI" -> ints(GL30C.GL_RGBA16UI, 4, true);
			case "R32I" -> ints(GL30C.GL_R32I, 1, false);
			case "RG32I" -> ints(GL30C.GL_RG32I, 2, false);
			case "RGB32I", "RGBA32I" -> ints(GL30C.GL_RGBA32I, 4, false);
			case "R32UI" -> ints(GL30C.GL_R32UI, 1, true);
			case "RG32UI" -> ints(GL30C.GL_RG32UI, 2, true);
			case "RGB32UI", "RGBA32UI" -> ints(GL30C.GL_RGBA32UI, 4, true);
			default -> Format.RGBA8;
		};
	}

	/**
	 * A new 2D texture, its contents undefined. Color textures filter linearly unless {@code nearest}; depth and integer ones
	 * never do. Coordinates clamp to the edge unless {@code repeat}.
	 */
	static int texture(Format format, int width, int height, boolean nearest, boolean repeat) {
		int id = GL11C.glGenTextures();
		setup(GL11C.GL_TEXTURE_2D, id);
		int filter = nearest || format.depth() || format.integer() ? GL11C.GL_NEAREST : GL11C.GL_LINEAR;
		int wrap = repeat ? GL11C.GL_REPEAT : GL12C.GL_CLAMP_TO_EDGE;
		GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, filter);
		GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, filter);
		GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, wrap);
		GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, wrap);
		GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
		if (format.depth()) GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE);
		GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
		GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, format.internal(), width, height, 0, format.format(), format.type(), (ByteBuffer) null);
		restoreActiveTexture();
		return id;
	}

	/** A new RGBA8 texture with {@code pixels} (RGBA bytes in memory, rows bottom to top as OpenGL reads them). */
	static int texture(int width, int height, IntBuffer pixels, boolean linear, boolean repeat) {
		int id = texture(Format.RGBA8, width, height, !linear, repeat);
		setup(GL11C.GL_TEXTURE_2D, id);
		GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 4);
		GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0);
		GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0);
		GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
		GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, width, height, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);
		restoreActiveTexture();
		return id;
	}

	/** Sets a texture's filtering and wrapping. */
	static void sampling(int texture, boolean linear, boolean repeat) {
		setup(GL11C.GL_TEXTURE_2D, texture);
		int filter = linear ? GL11C.GL_LINEAR : GL11C.GL_NEAREST;
		int wrap = repeat ? GL11C.GL_REPEAT : GL12C.GL_CLAMP_TO_EDGE;
		GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, filter);
		GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, filter);
		GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, wrap);
		GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, wrap);
		restoreActiveTexture();
	}

	/** A new RGBA8 texture of {@code width} by {@code height} that can be drawn into (see WorldScale). */
	public static int colorTexture(int width, int height) {
		return texture(Format.RGBA8, width, height, true, false);
	}

	/** A new depth texture of {@code width} by {@code height} that can be drawn into (see WorldScale). */
	public static int depthTexture(int width, int height) {
		return texture(Format.DEPTH, width, height, true, false);
	}

	/**
	 * Counts deleted textures: a new texture can get a deleted one's name, and a framebuffer still holding the deleted one
	 * would take it for attached already (see Framebuffer).
	 */
	private static int texturesDeleted;

	public static void deleteTexture(int texture) {
		if (texture <= 0) return;
		units();
		for (int u = 0; u < units; u++) {
			if (unitTextures[u] == texture) unitTextures[u] = -1;
		}
		GL11C.glDeleteTextures(texture);
		texturesDeleted++;
	}

	/** Clears a color texture to a color (integer formats get it rounded). */
	static void clear(int texture, Format format, float r, float g, float b, float a) {
		if (clearsTextures()) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				if (format.integer()) {
					GL44C.glClearTexImage(texture, 0, GL30C.GL_RGBA_INTEGER, format.type(), stack.ints(Math.round(r), Math.round(g), Math.round(b), Math.round(a)));
				} else {
					GL44C.glClearTexImage(texture, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, stack.floats(r, g, b, a));
				}
			}
			return;
		}
		withScratch(texture, false, () -> {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				switch (format.kind()) {
					case INT -> GL30C.glClearBufferiv(GL11C.GL_COLOR, 0, stack.ints(Math.round(r), Math.round(g), Math.round(b), Math.round(a)));
					case UINT -> GL30C.glClearBufferuiv(GL11C.GL_COLOR, 0, stack.ints(Math.round(r), Math.round(g), Math.round(b), Math.round(a)));
					default -> GL30C.glClearBufferfv(GL11C.GL_COLOR, 0, stack.floats(r, g, b, a));
				}
			}
		});
	}

	/** Clears a depth texture. */
	static void clearDepth(int texture, float depth) {
		if (clearsTextures()) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				GL44C.glClearTexImage(texture, 0, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, stack.floats(depth));
			}
			return;
		}
		withScratch(texture, true, () -> {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				GL30C.glClearBufferfv(GL11C.GL_DEPTH, 0, stack.floats(depth));
			}
		});
	}

	/** Runs {@code clear} with the texture alone in a framebuffer, writes unmasked and the scissor off. */
	private static void withScratch(int texture, boolean depth, Runnable clear) {
		if (scratchDraw == 0) scratchDraw = GL30C.glGenFramebuffers();
		GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, scratchDraw);
		attachOnly(GL30C.GL_FRAMEBUFFER, texture, depth);
		GlStateManager._colorMask(true, true, true, true);
		GlStateManager._depthMask(true);
		GlStateManager._disableScissorTest();
		clear.run();
		GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, drawFramebuffer);
	}

	private static void attachOnly(int target, int texture, boolean depth) {
		GL30C.glFramebufferTexture2D(target, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, depth ? 0 : texture, 0);
		GL30C.glFramebufferTexture2D(target, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, depth ? texture : 0, 0);
		if (target == GL30C.GL_READ_FRAMEBUFFER) GL11C.glReadBuffer(depth ? GL11C.GL_NONE : GL30C.GL_COLOR_ATTACHMENT0);
		else GL20C.glDrawBuffers(depth ? GL11C.GL_NONE : GL30C.GL_COLOR_ATTACHMENT0);
	}

	/** Copies a texture's contents to another the same size and format. */
	static void copy(int from, int to, int width, int height, boolean depth) {
		if (copiesImages()) {
			GL43C.glCopyImageSubData(from, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, to, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
			return;
		}
		if (scratchRead == 0) scratchRead = GL30C.glGenFramebuffers();
		if (scratchDraw == 0) scratchDraw = GL30C.glGenFramebuffers();
		GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, scratchRead);
		attachOnly(GL30C.GL_READ_FRAMEBUFFER, from, depth);
		GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, scratchDraw);
		attachOnly(GL30C.GL_DRAW_FRAMEBUFFER, to, depth);
		GlStateManager._disableScissorTest();
		GL30C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, depth ? GL11C.GL_DEPTH_BUFFER_BIT : GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
		GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, drawFramebuffer);
	}

	/** Copies the color of the framebuffer {@code from} (attachment 0) into {@code to}'s, scaled, filtered linearly. */
	static void blit(int from, int fromWidth, int fromHeight, int to, int toWidth, int toHeight) {
		GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, from);
		GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, to);
		GlStateManager._disableScissorTest();
		GL30C.glBlitFramebuffer(0, 0, fromWidth, fromHeight, 0, 0, toWidth, toHeight, GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_LINEAR);
		GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, drawFramebuffer);
	}

	// ---- Framebuffers ----

	/** The game bound a framebuffer (GlStateManager#_glBindFramebuffer). */
	public static void boundByGame(int target, int id) {
		if (target == GL30C.GL_FRAMEBUFFER || target == GL30C.GL_DRAW_FRAMEBUFFER) drawFramebuffer = id;
	}

	/** The framebuffer drawn into now. */
	public static int drawFramebuffer() {
		return drawFramebuffer;
	}

	/** Binds a framebuffer for drawing and reading. */
	public static void bindFramebuffer(int id) {
		GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, id);
		drawFramebuffer = id;
	}

	public static void viewport(int width, int height) {
		GlStateManager._viewport(0, 0, width, height);
	}

	/** A framebuffer with {@code color} as its color attachment and {@code depth} as its depth (0 for none), for WorldScale. */
	public static int framebuffer(int color, int depth) {
		int previous = drawFramebuffer;
		int id = GL30C.glGenFramebuffers();
		GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, id);
		GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, color, 0);
		GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, depth, 0);
		GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, previous);
		return id;
	}

	/** Deletes a framebuffer made by {@link #framebuffer}. */
	public static void deleteFramebuffer(int id) {
		if (id != 0) GL30C.glDeleteFramebuffers(id);
	}

	/** Our framebuffer, remembering what's attached and drawn into so that nothing is set twice. */
	static final class Framebuffer implements AutoCloseable {
		final int id;
		private final int[] colors = new int[16];
		private int depth = -1;
		private int @Nullable [] drawn;
		/** {@link #texturesDeleted} when what's attached was last set: past it, a name can be another texture now. */
		private int seen;

		Framebuffer() {
			this.id = GL30C.glGenFramebuffers();
			Arrays.fill(this.colors, -1);
		}

		/** Binds it. */
		Framebuffer bind() {
			if (drawFramebuffer != this.id) bindFramebuffer(this.id);
			return this;
		}

		/** Attaches a texture as color attachment {@code index} (0 for none); it must be bound. */
		Framebuffer color(int index, int texture) {
			this.forgetIfDeleted();
			if (index >= this.colors.length || this.colors[index] == texture) return this;
			this.colors[index] = texture;
			GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0 + index, GL11C.GL_TEXTURE_2D, texture, 0);
			return this;
		}

		/** Attaches a depth texture (0 for none); it must be bound. */
		Framebuffer depth(int texture) {
			this.forgetIfDeleted();
			if (this.depth == texture) return this;
			this.depth = texture;
			GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, texture, 0);
			return this;
		}

		private void forgetIfDeleted() {
			if (this.seen == texturesDeleted) return;
			this.seen = texturesDeleted;
			Arrays.fill(this.colors, -1);
			this.depth = -1;
		}

		/** Which attachment each fragment output goes to (-1: nowhere); it must be bound. */
		Framebuffer drawBuffers(int[] attachments) {
			if (Arrays.equals(this.drawn, attachments)) return this;
			this.drawn = attachments.clone();
			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer buffers = stack.mallocInt(Math.max(1, attachments.length));
				for (int a : attachments) buffers.put(a < 0 ? GL11C.GL_NONE : GL30C.GL_COLOR_ATTACHMENT0 + a);
				if (attachments.length == 0) buffers.put(GL11C.GL_NONE);
				buffers.flip();
				GL20C.glDrawBuffers(buffers);
			}
			return this;
		}

		@Override
		public void close() {
			if (drawFramebuffer == this.id) bindFramebuffer(0);
			GL30C.glDeleteFramebuffers(this.id);
		}
	}

	/** Draw buffers 0..n-1 to attachments 0..n-1: what a fullscreen pass writes. */
	static int[] all(int n) {
		int[] a = new int[n];
		for (int i = 0; i < n; i++) a[i] = i;
		return a;
	}

	// ---- Programs ----

	/** A compiled and linked program, which may still be compiling in the background. */
	static final class Program implements AutoCloseable {
		final String label;
		final int id;
		private final int[] shaders;
		private final IntFunction<String> vertexOrigin, fragmentOrigin;
		private final @Nullable Consumer<Program> linked;
		private boolean checked;
		private @Nullable String error;

		private Program(String label, int[] shaders, IntFunction<String> vertexOrigin, IntFunction<String> fragmentOrigin,
				@Nullable Consumer<Program> linked) {
			this.label = label;
			this.id = GL20C.glCreateProgram();
			this.shaders = shaders;
			this.vertexOrigin = vertexOrigin;
			this.fragmentOrigin = fragmentOrigin;
			this.linked = linked;
		}

		/** Whether it's done compiling (linked or failed), without waiting for it. */
		boolean done() {
			if (this.checked) return true;
			return !compilesInParallel() || GL20C.glGetProgrami(this.id, COMPLETION_STATUS) != GL11C.GL_FALSE;
		}

		/** Whether it can be drawn with: it's done compiling and linked. Waits for it if it's still compiling. */
		boolean ready() {
			if (!this.checked) this.check();
			return this.error == null;
		}

		/** Why it didn't compile or link, once it's {@link #done}; null if it did. */
		@Nullable String error() {
			if (!this.checked) this.check();
			return this.error;
		}

		private void check() {
			this.checked = true;
			StringBuilder log = new StringBuilder();
			for (int i = 0; i < this.shaders.length; i++) {
				int s = this.shaders[i];
				if (GL20C.glGetShaderi(s, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
					IntFunction<String> origin = i == 0 ? this.vertexOrigin : this.fragmentOrigin;
					log.append(i == 0 ? "vertex: " : "fragment: ").append(mapLog(GL20C.glGetShaderInfoLog(s), origin)).append('\n');
				}
			}
			if (log.isEmpty() && GL20C.glGetProgrami(this.id, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
				log.append("link: ").append(GL20C.glGetProgramInfoLog(this.id)).append('\n');
			}
			for (int s : this.shaders) {
				GL20C.glDetachShader(this.id, s);
				GL20C.glDeleteShader(s);
			}
			if (!log.isEmpty()) {
				this.error = log.toString().strip();
				return;
			}
			if (this.linked != null) {
				int before = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
				GL20C.glUseProgram(this.id);
				this.linked.accept(this);
				GL20C.glUseProgram(before);
			}
		}

		/** Sets a sampler's or an int's uniform; only from the {@code linked} callback, where it's in use. */
		void uniform(String name, int value) {
			int location = GL20C.glGetUniformLocation(this.id, name);
			if (location >= 0) GL20C.glUniform1i(location, value);
		}

		int location(String name) {
			return GL20C.glGetUniformLocation(this.id, name);
		}

		/** Binds a uniform block to a binding, if the program has it. */
		void block(String name, int binding) {
			int index = GL31C.glGetUniformBlockIndex(this.id, name);
			if (index != GL31C.GL_INVALID_INDEX) GL31C.glUniformBlockBinding(this.id, index, binding);
		}

		@Override
		public void close() {
			if (!this.checked) {
				for (int s : this.shaders) {
					GL20C.glDetachShader(this.id, s);
					GL20C.glDeleteShader(s);
				}
				this.checked = true;
			}
			GL20C.glDeleteProgram(this.id);
		}
	}

	/**
	 * Compiles and links a vertex and fragment shader, attribute {@code attributes[i]} at location i (null ones skipped).
	 * {@code linked} runs once it's linked, with it in use, to set its samplers and blocks. Lines in the compile log are
	 * named by {@code vertexOrigin} and {@code fragmentOrigin}.
	 */
	static Program program(String label, String vertex, String fragment, @Nullable String[] attributes, IntFunction<String> vertexOrigin,
			IntFunction<String> fragmentOrigin, @Nullable Consumer<Program> linked) {
		compilesInParallel();
		int vs = shader(GL20C.GL_VERTEX_SHADER, vertex);
		int fs = shader(GL20C.GL_FRAGMENT_SHADER, fragment);
		Program p = new Program(label, new int[] {vs, fs}, vertexOrigin, fragmentOrigin, linked);
		GL20C.glAttachShader(p.id, vs);
		GL20C.glAttachShader(p.id, fs);
		for (int i = 0; i < attributes.length; i++) {
			if (attributes[i] != null) GL20C.glBindAttribLocation(p.id, i, attributes[i]);
		}
		GL20C.glLinkProgram(p.id);
		return p;
	}

	/** Compiles and links a compute shader (OpenGL 4.3). */
	static Program compute(String label, String source, IntFunction<String> origin, @Nullable Consumer<Program> linked) {
		compilesInParallel();
		int cs = shader(GL43C.GL_COMPUTE_SHADER, source);
		Program p = new Program(label, new int[] {cs}, origin, origin, linked);
		GL20C.glAttachShader(p.id, cs);
		GL20C.glLinkProgram(p.id);
		return p;
	}

	private static int shader(int type, String source) {
		int s = GL20C.glCreateShader(type);
		GL20C.glShaderSource(s, source);
		GL20C.glCompileShader(s);
		return s;
	}

	/** A compile log with the lines it names given as where they came from in the pack. */
	private static String mapLog(String log, IntFunction<String> origin) {
		StringBuilder out = new StringBuilder();
		for (String line : log.split("\n")) {
			if (line.isBlank()) continue;
			Matcher m = LOG_LINE.matcher(line);
			if (m.find()) {
				try {
					line = line.substring(0, m.start()) + origin.apply(Integer.parseInt(m.group(1))) + line.substring(m.end());
				} catch (NumberFormatException ignored) {
				}
			}
			if (!out.isEmpty()) out.append("\n  ");
			out.append(line.strip());
		}
		return out.toString();
	}

	/** Uses a program. The game's shaders then bind theirs again (ShaderInstance's last program is forgotten). */
	static void useProgram(int id) {
		GlStateManager._glUseProgram(id);
		com.afterburner.labs.mixin.shader.ShaderInstanceAccessor.afterburner$setLastProgramId(-1);
	}

	// ---- Buffers ----

	/** A new uniform buffer of {@code size} bytes. */
	static int uniformBuffer(int size) {
		int id = GL15C.glGenBuffers();
		GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, id);
		GL15C.glBufferData(GL31C.GL_UNIFORM_BUFFER, Math.max(16, size), GL15C.GL_DYNAMIC_DRAW);
		GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, 0);
		return id;
	}

	/** Writes {@code data} (from its position to its limit) at the start of a uniform buffer. */
	static void upload(int buffer, ByteBuffer data) {
		GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, buffer);
		GL15C.glBufferSubData(GL31C.GL_UNIFORM_BUFFER, 0, data);
		GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, 0);
	}

	/** Binds a uniform buffer to a binding, if it isn't already. */
	static void bindUniformBuffer(int binding, int buffer) {
		if (binding < UNIFORM_BINDINGS && uniformBuffers[binding] == buffer) return;
		GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, binding, buffer);
		if (binding < UNIFORM_BINDINGS) uniformBuffers[binding] = buffer;
	}

	static void deleteBuffer(int buffer) {
		if (buffer <= 0) return;
		for (int i = 0; i < UNIFORM_BINDINGS; i++) {
			if (uniformBuffers[i] == buffer) uniformBuffers[i] = -1;
		}
		GL15C.glDeleteBuffers(buffer);
	}

	/** Draws one triangle over the screen, with no vertex buffer (a fullscreen pass's vertex shader makes the corners). */
	static void drawFullscreen() {
		if (emptyArray == 0) emptyArray = GL30C.glGenVertexArrays();
		GlStateManager._glBindVertexArray(emptyArray);
		BufferUploader.invalidate();
		GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
		GlStateManager._glBindVertexArray(0);
	}

	/**
	 * The state a fullscreen pass draws with: no depth test, no culling, no blending, every channel written. {@link #baseline}
	 * puts back what the world is drawn with.
	 */
	static void fullscreenState() {
		restoreBlend();
		GlStateManager._disableDepthTest();
		GlStateManager._depthMask(false);
		GlStateManager._disableCull();
		GlStateManager._disableBlend();
		GlStateManager._disableScissorTest();
		GlStateManager._colorMask(true, true, true, true);
	}

	/** The game's usual state while it draws the world: depth tested (less or equal) and written, back faces culled, no blending. */
	public static void baseline() {
		GlStateManager._enableDepthTest();
		GlStateManager._depthFunc(GL11C.GL_LEQUAL);
		GlStateManager._depthMask(true);
		GlStateManager._enableCull();
		GlStateManager._disableBlend();
		GlStateManager._colorMask(true, true, true, true);
	}

	// ---- Blending ----
	// GlStateManager keeps the blend state in a private class; this follows it (from the mixin on its methods) so that a
	// pack's blend, set behind its back, can be put back to what it thinks is set.

	private static boolean blendOn;
	/** GlStateManager's idea of the factors (it compares with these before setting them). */
	private static int cachedSrcRgb = GL11C.GL_ONE, cachedDstRgb = GL11C.GL_ZERO, cachedSrcAlpha = GL11C.GL_ONE, cachedDstAlpha = GL11C.GL_ZERO;
	/** The factors OpenGL has from it (glBlendFunc sets the alpha ones too). */
	private static int srcRgb = GL11C.GL_ONE, dstRgb = GL11C.GL_ZERO, srcAlpha = GL11C.GL_ONE, dstAlpha = GL11C.GL_ZERO;
	private static boolean blendOverridden;

	/** Before GlStateManager#_enableBlend or _disableBlend. */
	public static void gameBlend(boolean on) {
		restoreBlend();
		blendOn = on;
	}

	/** Before GlStateManager#_blendFunc. */
	public static void gameBlendFunc(int src, int dst) {
		restoreBlend();
		if (src != cachedSrcRgb || dst != cachedDstRgb) {
			cachedSrcRgb = src;
			cachedDstRgb = dst;
			srcRgb = src;
			dstRgb = dst;
			srcAlpha = src;
			dstAlpha = dst;
		}
	}

	/** Before GlStateManager#_blendFuncSeparate. */
	public static void gameBlendFuncSeparate(int srcColor, int dstColor, int srcA, int dstA) {
		restoreBlend();
		if (srcColor != cachedSrcRgb || dstColor != cachedDstRgb || srcA != cachedSrcAlpha || dstA != cachedDstAlpha) {
			cachedSrcRgb = srcColor;
			cachedDstRgb = dstColor;
			cachedSrcAlpha = srcA;
			cachedDstAlpha = dstA;
			srcRgb = srcColor;
			dstRgb = dstColor;
			srcAlpha = srcA;
			dstAlpha = dstA;
		}
	}

	/** Whether the game blends what it draws now. */
	static boolean blending() {
		return blendOn;
	}

	/**
	 * Blends as a pack says for what's drawn next: {@code all} for every attachment (null: as the game blends; empty: off), and
	 * per attachment {@code perAttachment[i]} where it's not null.
	 */
	static void overrideBlend(@Nullable Optional<PackBlending.Factors> all, Optional<PackBlending.Factors> @Nullable [] perAttachment) {
		if (all == null && perAttachment == null) return;
		blendOverridden = true;
		boolean on = blendOn;
		if (all != null) {
			on = all.isPresent();
			if (on) {
				PackBlending.Factors f = all.get();
				GL14C.glBlendFuncSeparate(factor(f.srcColor()), factor(f.dstColor()), factor(f.srcAlpha()), factor(f.dstAlpha()));
			}
			if (on) GL11C.glEnable(GL11C.GL_BLEND);
			else GL11C.glDisable(GL11C.GL_BLEND);
		}
		if (perAttachment != null) {
			for (int i = 0; i < perAttachment.length; i++) {
				Optional<PackBlending.Factors> f = perAttachment[i];
				if (f == null) continue;
				if (f.isPresent()) {
					// The factors are one function for every attachment (per attachment is OpenGL 4.0): the pass's.
					if (!on) {
						PackBlending.Factors g = f.get();
						GL14C.glBlendFuncSeparate(factor(g.srcColor()), factor(g.dstColor()), factor(g.srcAlpha()), factor(g.dstAlpha()));
					}
					GL30C.glEnablei(GL11C.GL_BLEND, i);
				} else {
					GL30C.glDisablei(GL11C.GL_BLEND, i);
				}
			}
		}
	}

	/** Puts back the game's blend after {@link #overrideBlend}. */
	public static void restoreBlend() {
		if (!blendOverridden) return;
		blendOverridden = false;
		if (blendOn) GL11C.glEnable(GL11C.GL_BLEND);
		else GL11C.glDisable(GL11C.GL_BLEND);
		GL14C.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
	}

	private static int factor(String name) {
		return switch (name) {
			case "ZERO" -> GL11C.GL_ZERO;
			case "ONE" -> GL11C.GL_ONE;
			case "SRC_COLOR" -> GL11C.GL_SRC_COLOR;
			case "ONE_MINUS_SRC_COLOR" -> GL11C.GL_ONE_MINUS_SRC_COLOR;
			case "DST_COLOR" -> GL11C.GL_DST_COLOR;
			case "ONE_MINUS_DST_COLOR" -> GL11C.GL_ONE_MINUS_DST_COLOR;
			case "SRC_ALPHA" -> GL11C.GL_SRC_ALPHA;
			case "ONE_MINUS_SRC_ALPHA" -> GL11C.GL_ONE_MINUS_SRC_ALPHA;
			case "DST_ALPHA" -> GL11C.GL_DST_ALPHA;
			case "ONE_MINUS_DST_ALPHA" -> GL11C.GL_ONE_MINUS_DST_ALPHA;
			case "SRC_ALPHA_SATURATE" -> GL11C.GL_SRC_ALPHA_SATURATE;
			default -> GL11C.GL_ONE;
		};
	}
}
