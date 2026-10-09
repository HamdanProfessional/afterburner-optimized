package com.afterburner.labs.shaderpack.game;

import com.afterburner.labs.shaderpack.PackImages;
import com.afterburner.labs.shaderpack.PackLoader;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A pack's custom images and storage buffers (shaders.properties' image.* and bufferObject.*), as plain OpenGL objects the
 * game's renderer doesn't know about: images are bound to image units and, under their sampler names, to texture units from
 * {@link #FIRST_SAMPLER_UNIT}; storage buffers to their index. The units are set in the pack's programs by
 * {@link ExternalBindings}, and kept bound here: the game's renderer never uses them.
 */
final class PackStorage implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger("afterburner");
	/** Texture units of the images' samplers: past those the game's pipelines use (one per sampler, from 0). */
	static final int FIRST_SAMPLER_UNIT = 48;
	static final int SAMPLER_UNITS = 16;
	/** A warning past this much memory in images and buffers. */
	private static final long LARGE = 1L << 30;

	private static @Nullable Boolean supported;

	/** One custom image, made again when the render size changes if it's relative. */
	private static final class Image {
		final PackImages.Image spec;
		final int target;
		final int internalFormat;
		final int clearFormat;
		final boolean imageUnit;
		final int components;
		int texture;
		int width, height, depth;
		/** Its image unit, or -1; its sampler's texture unit, or -1. */
		int unit = -1, samplerUnit = -1;

		Image(PackImages.Image spec, int target, int internalFormat, int clearFormat, boolean imageUnit, int components) {
			this.spec = spec;
			this.target = target;
			this.internalFormat = internalFormat;
			this.clearFormat = clearFormat;
			this.imageUnit = imageUnit;
			this.components = components;
		}
	}

	private static final class Buffer {
		final PackImages.Buffer spec;
		int id;
		long size;

		Buffer(PackImages.Buffer spec) {
			this.spec = spec;
		}
	}

	private final List<Image> images = new ArrayList<>();
	private final List<Buffer> buffers = new ArrayList<>();
	private final int linear;
	private final int nearest;
	private final boolean clearTexture;
	private int renderWidth = -1, renderHeight = -1;
	private boolean clearedOnce;

	/**
	 * Whether this machine runs what a pack's images, storage buffers and compute programs need: OpenGL 4.3 with images in
	 * vertex shaders, and not macOS (its OpenGL stops at 4.1). Asked on the render thread.
	 */
	static boolean supported() {
		Boolean s = supported;
		if (s == null) {
			String why = null;
			try {
				GLCapabilities caps = GL.getCapabilities();
				String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
				if (os.contains("mac")) why = "macOS";
				else if (!caps.OpenGL43) why = "the OpenGL context is " + GL11C.glGetString(GL11C.GL_VERSION) + ", not 4.3";
				else if (!caps.GL_ARB_shader_image_load_store || !caps.GL_ARB_shader_storage_buffer_object) why = "no image load/store or storage buffers";
				else if (GL11C.glGetInteger(GL42C.GL_MAX_VERTEX_IMAGE_UNIFORMS) <= 0) why = "no images in vertex shaders";
				else if (GL11C.glGetInteger(GL42C.GL_MAX_IMAGE_UNITS) < 8) why = "fewer than 8 image units";
			} catch (RuntimeException | LinkageError e) {
				why = e.toString();
			}
			supported = s = why == null;
			LOGGER.info("[Afterburner] Shader pack images, storage buffers and compute programs: {}", why == null ? "supported" : "not supported here (" + why + ")");
		}
		return s;
	}

	PackStorage(PackLoader.Loaded pack, List<String> warnings) {
		this.clearTexture = GL.getCapabilities().OpenGL44 || GL.getCapabilities().GL_ARB_clear_texture;
		this.linear = sampler(GL11C.GL_LINEAR);
		this.nearest = sampler(GL11C.GL_NEAREST);
		Map<String, ExternalBindings.Slot> slots = new LinkedHashMap<>();
		int maxImageUnits = GL11C.glGetInteger(GL42C.GL_MAX_IMAGE_UNITS);
		int nextImageUnit = 0, nextSamplerUnit = FIRST_SAMPLER_UNIT, binding = 0;

		for (PackImages.Image spec : pack.images.images.values()) {
			Format f = FORMATS.get(spec.internalFormat());
			if (f == null) {
				warnings.add("image." + spec.name() + ": unknown internal format " + spec.internalFormat());
				continue;
			}
			int target = switch (spec.dimensions()) {
				case 1 -> GL11C.GL_TEXTURE_1D;
				case 2 -> GL11C.GL_TEXTURE_2D;
				default -> GL12C.GL_TEXTURE_3D;
			};
			Image image = new Image(spec, target, f.internal, clearFormat(f), f.imageUnit, f.components);
			if (!f.imageUnit) {
				warnings.add("image." + spec.name() + ": " + spec.internalFormat() + " can't be written as an image, only read");
			} else if (nextImageUnit >= maxImageUnits) {
				warnings.add("image." + spec.name() + ": out of image units (this GPU has " + maxImageUnits + ")");
			} else {
				image.unit = nextImageUnit++;
				slots.put(spec.name(), new ExternalBindings.Slot(ExternalBindings.Kind.IMAGE, binding++, image.unit));
			}
			if (spec.sampler() != null) {
				if (nextSamplerUnit >= FIRST_SAMPLER_UNIT + SAMPLER_UNITS) {
					warnings.add("image." + spec.name() + ": too many image samplers");
				} else {
					image.samplerUnit = nextSamplerUnit++;
					slots.put(spec.sampler(), new ExternalBindings.Slot(ExternalBindings.Kind.SAMPLER, binding++, image.samplerUnit));
				}
			}
			this.images.add(image);
		}
		for (PackImages.Buffer spec : pack.images.buffers.values()) this.buffers.add(new Buffer(spec));
		for (Map.Entry<String, Integer> e : pack.bufferBlocks.entrySet()) {
			if (e.getValue() >= 0) slots.put(e.getKey(), new ExternalBindings.Slot(ExternalBindings.Kind.BUFFER, binding++, e.getValue()));
		}
		ExternalBindings.set(slots);
	}

	boolean isEmpty() {
		return this.images.isEmpty() && this.buffers.isEmpty();
	}

	/**
	 * The size of the biggest 3D image (a voxel volume around the camera, as packs use them), for the shadow pass to draw at
	 * least that much around the camera; null without one.
	 */
	int @Nullable [] volume() {
		int[] out = null;
		for (Image image : this.images) {
			if (image.target != GL12C.GL_TEXTURE_3D) continue;
			if (out == null) out = new int[3];
			out[0] = Math.max(out[0], image.spec.width());
			out[1] = Math.max(out[1], image.spec.height());
			out[2] = Math.max(out[2], image.spec.depth());
		}
		return out;
	}

	/** Makes what isn't made yet, or what the render size changed (relative images and buffers). */
	void resize(int width, int height, List<String> warnings) {
		if (width == this.renderWidth && height == this.renderHeight) return;
		boolean first = this.renderWidth < 0;
		this.renderWidth = width;
		this.renderHeight = height;
		long total = 0;
		for (Image image : this.images) {
			if (!first && !image.spec.relative()) {
				total += image.spec.texels(width, height) * image.components * 4L;
				continue;
			}
			if (image.texture != 0) delete(image);
			this.create(image, width, height);
			total += image.spec.texels(width, height) * image.components * 4L;
		}
		for (Buffer buffer : this.buffers) {
			long size = buffer.spec.size(width, height);
			total += size;
			if (!first && size == buffer.size) continue;
			if (buffer.id != 0) GL15C.glDeleteBuffers(buffer.id);
			buffer.id = 0;
			buffer.size = 0;
			drainErrors();
			int id = GL15C.glGenBuffers();
			GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, id);
			GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, size, GL15C.GL_DYNAMIC_DRAW);
			if (GL11C.glGetError() == GL11C.GL_OUT_OF_MEMORY) {
				GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
				GL15C.glDeleteBuffers(id);
				warnings.add("bufferObject." + buffer.spec.index() + ": not enough video memory for " + (size >> 20) + " MB");
				continue;
			}
			GL43C.glClearBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, GL30C.GL_R8UI, GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
			GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
			buffer.id = id;
			buffer.size = size;
		}
		if (first && total > LARGE) warnings.add("The pack's images and storage buffers take about " + (total >> 20) + " MB of video memory");
		this.clearedOnce = false;
	}

	private void create(Image image, int renderWidth, int renderHeight) {
		PackImages.Image spec = image.spec;
		image.width = spec.relative() ? Math.max(1, (int) (renderWidth * spec.scaleX())) : spec.width();
		image.height = spec.relative() ? Math.max(1, (int) (renderHeight * spec.scaleY())) : Math.max(1, spec.height());
		image.depth = Math.max(1, spec.depth());
		image.texture = GL11C.glGenTextures();
		this.bindForSetup(image);
		switch (image.target) {
			case GL11C.GL_TEXTURE_1D -> GL42C.glTexStorage1D(image.target, 1, image.internalFormat, image.width);
			case GL11C.GL_TEXTURE_2D -> GL42C.glTexStorage2D(image.target, 1, image.internalFormat, image.width, image.height);
			default -> GL42C.glTexStorage3D(image.target, 1, image.internalFormat, image.width, image.height, image.depth);
		}
		int filter = spec.integer() ? GL11C.GL_NEAREST : GL11C.GL_LINEAR;
		GL11C.glTexParameteri(image.target, GL11C.GL_TEXTURE_MIN_FILTER, filter);
		GL11C.glTexParameteri(image.target, GL11C.GL_TEXTURE_MAG_FILTER, filter);
		GL11C.glTexParameteri(image.target, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
		GL11C.glTexParameteri(image.target, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
		GL11C.glTexParameteri(image.target, GL12C.GL_TEXTURE_WRAP_R, GL12C.GL_CLAMP_TO_EDGE);
		this.unbindAfterSetup(image);
		this.clear(image);
	}

	/**
	 * Binds an image's texture to the first sampler unit of ours to set it up. 2D textures go through the game's state
	 * manager, which keeps track of what each unit has; other kinds it doesn't track.
	 */
	private void bindForSetup(Image image) {
		GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + FIRST_SAMPLER_UNIT + SAMPLER_UNITS - 1);
		if (image.target == GL11C.GL_TEXTURE_2D) GlStateManager._bindTexture(image.texture);
		else GL11C.glBindTexture(image.target, image.texture);
	}

	private void unbindAfterSetup(Image image) {
		if (image.target == GL11C.GL_TEXTURE_2D) GlStateManager._bindTexture(0);
		else GL11C.glBindTexture(image.target, 0);
		GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
	}

	private static void delete(Image image) {
		if (image.target == GL11C.GL_TEXTURE_2D) GlStateManager._deleteTexture(image.texture);
		else GL11C.glDeleteTextures(image.texture);
		image.texture = 0;
	}

	/** Zeroes an image: glClearTexImage, or (without it) uploading zeros a layer at a time. */
	private void clear(Image image) {
		if (image.texture == 0) return;
		boolean integer = image.spec.integer();
		if (this.clearTexture) {
			GL44C.glClearTexImage(image.texture, 0, image.clearFormat, integer ? GL11C.GL_UNSIGNED_INT : GL11C.GL_FLOAT, (ByteBuffer) null);
			return;
		}
		int rowBytes = (image.width * image.components + 3) & ~3;
		ByteBuffer zeros = MemoryUtil.memCalloc(rowBytes * image.height);
		try {
			GlStateManager._pixelStore(GL11C.GL_UNPACK_ALIGNMENT, 4);
			GlStateManager._pixelStore(GL11C.GL_UNPACK_ROW_LENGTH, 0);
			GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_ROWS, 0);
			GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
			GL15C.glBindBuffer(GL30C.GL_PIXEL_UNPACK_BUFFER, 0);
			this.bindForSetup(image);
			switch (image.target) {
				case GL11C.GL_TEXTURE_1D -> GL11C.glTexSubImage1D(image.target, 0, 0, image.width, image.clearFormat, GL11C.GL_UNSIGNED_BYTE, zeros);
				case GL11C.GL_TEXTURE_2D -> GL11C.glTexSubImage2D(image.target, 0, 0, 0, image.width, image.height, image.clearFormat,
					GL11C.GL_UNSIGNED_BYTE, zeros);
				default -> {
					for (int z = 0; z < image.depth; z++) {
						GL12C.glTexSubImage3D(image.target, 0, 0, 0, z, image.width, image.height, 1, image.clearFormat, GL11C.GL_UNSIGNED_BYTE, zeros);
					}
				}
			}
			this.unbindAfterSetup(image);
		} finally {
			MemoryUtil.memFree(zeros);
		}
	}

	/**
	 * At the start of a frame, before the shadow pass: zeroes the images that ask for it (and all of them the first time), except
	 * those in {@code keep} (written by a shadow map kept this frame). True if one of those had to be zeroed all the same (being
	 * new): then the shadow map must be drawn again.
	 */
	boolean clearImages(Set<String> keep) {
		boolean clearedKept = false;
		for (Image image : this.images) {
			boolean kept = keep.contains(image.spec.name());
			if (!this.clearedOnce || image.spec.clear() && !kept) {
				this.clear(image);
				clearedKept |= kept;
			}
		}
		this.clearedOnce = true;
		return clearedKept;
	}

	/** Binds everything to its unit. The game's renderer leaves these units alone, so once a frame is enough. */
	void bind() {
		for (Image image : this.images) {
			if (image.texture == 0) continue;
			if (image.unit >= 0) {
				GL42C.glBindImageTexture(image.unit, image.texture, 0, image.target == GL12C.GL_TEXTURE_3D, 0, GL15C.GL_READ_WRITE,
					image.internalFormat);
			}
			if (image.samplerUnit >= 0) {
				GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + image.samplerUnit);
				if (image.target == GL11C.GL_TEXTURE_2D) GlStateManager._bindTexture(image.texture);
				else GL11C.glBindTexture(image.target, image.texture);
				GL33C.glBindSampler(image.samplerUnit, image.spec.integer() ? this.nearest : this.linear);
			}
		}
		GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
		for (Buffer buffer : this.buffers) {
			if (buffer.id != 0) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, buffer.spec.index(), buffer.id);
		}
		GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
	}

	/** Makes what was written to images and buffers so far visible to what reads them next. */
	void barrier() {
		if (!this.isEmpty()) GL42C.glMemoryBarrier(GL42C.GL_ALL_BARRIER_BITS);
	}

	@Override
	public void close() {
		ExternalBindings.clear();
		for (Image image : this.images) {
			if (image.texture != 0) delete(image);
		}
		for (Buffer buffer : this.buffers) {
			if (buffer.id != 0) GL15C.glDeleteBuffers(buffer.id);
			buffer.id = 0;
		}
		for (Image image : this.images) {
			if (image.unit >= 0) GL42C.glBindImageTexture(image.unit, 0, 0, false, 0, GL15C.GL_READ_WRITE, GL30C.GL_R32UI);
			if (image.samplerUnit >= 0) GL33C.glBindSampler(image.samplerUnit, 0);
		}
		GL33C.glDeleteSamplers(this.linear);
		GL33C.glDeleteSamplers(this.nearest);
	}

	private static int sampler(int filter) {
		int id = GL33C.glGenSamplers();
		GL33C.glSamplerParameteri(id, GL11C.GL_TEXTURE_MIN_FILTER, filter);
		GL33C.glSamplerParameteri(id, GL11C.GL_TEXTURE_MAG_FILTER, filter);
		GL33C.glSamplerParameteri(id, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
		GL33C.glSamplerParameteri(id, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
		GL33C.glSamplerParameteri(id, GL12C.GL_TEXTURE_WRAP_R, GL12C.GL_CLAMP_TO_EDGE);
		return id;
	}

	private static void drainErrors() {
		for (int i = 0; i < 16 && GL11C.glGetError() != GL11C.GL_NO_ERROR; i++) {
			// Someone else's; not ours to report.
		}
	}

	/** The pixel format a clear or zero upload of the image uses: its components, as integers for an integer format. */
	private static int clearFormat(Format f) {
		boolean integer = f.integer;
		return switch (f.components) {
			case 1 -> integer ? GL30C.GL_RED_INTEGER : GL11C.GL_RED;
			case 2 -> integer ? GL30C.GL_RG_INTEGER : GL30C.GL_RG;
			case 3 -> integer ? GL30C.GL_RGB_INTEGER : GL11C.GL_RGB;
			default -> integer ? GL30C.GL_RGBA_INTEGER : GL11C.GL_RGBA;
		};
	}

	/** An internal format: its GL enum, components, whether it's integer, and whether images can be bound with it. */
	private record Format(int internal, int components, boolean integer, boolean imageUnit) {}

	private static final Map<String, Format> FORMATS = new LinkedHashMap<>();

	private static void format(String name, int internal, int components, boolean integer, boolean imageUnit) {
		FORMATS.put(name, new Format(internal, components, integer, imageUnit));
	}

	static {
		format("r8", GL30C.GL_R8, 1, false, true);
		format("r8_snorm", GL31C.GL_R8_SNORM, 1, false, true);
		format("r16", GL30C.GL_R16, 1, false, true);
		format("r16_snorm", GL31C.GL_R16_SNORM, 1, false, true);
		format("rg8", GL30C.GL_RG8, 2, false, true);
		format("rg8_snorm", GL31C.GL_RG8_SNORM, 2, false, true);
		format("rg16", GL30C.GL_RG16, 2, false, true);
		format("rg16_snorm", GL31C.GL_RG16_SNORM, 2, false, true);
		format("rgb8", GL11C.GL_RGB8, 3, false, false);
		format("rgb8_snorm", GL31C.GL_RGB8_SNORM, 3, false, false);
		format("rgb16", GL11C.GL_RGB16, 3, false, false);
		format("rgb16_snorm", GL31C.GL_RGB16_SNORM, 3, false, false);
		format("rgba8", GL11C.GL_RGBA8, 4, false, true);
		format("rgba8_snorm", GL31C.GL_RGBA8_SNORM, 4, false, true);
		format("rgba16", GL11C.GL_RGBA16, 4, false, true);
		format("rgba16_snorm", GL31C.GL_RGBA16_SNORM, 4, false, true);
		format("rgb10_a2", GL11C.GL_RGB10_A2, 4, false, true);
		format("rgb5_a1", GL11C.GL_RGB5_A1, 4, false, false);
		format("srgb8", GL30C.GL_SRGB8, 3, false, false);
		format("srgb8_alpha8", GL30C.GL_SRGB8_ALPHA8, 4, false, false);
		format("r16f", GL30C.GL_R16F, 1, false, true);
		format("rg16f", GL30C.GL_RG16F, 2, false, true);
		format("rgb16f", GL30C.GL_RGB16F, 3, false, false);
		format("rgba16f", GL30C.GL_RGBA16F, 4, false, true);
		format("r32f", GL30C.GL_R32F, 1, false, true);
		format("rg32f", GL30C.GL_RG32F, 2, false, true);
		format("rgb32f", GL30C.GL_RGB32F, 3, false, false);
		format("rgba32f", GL30C.GL_RGBA32F, 4, false, true);
		format("r11f_g11f_b10f", GL30C.GL_R11F_G11F_B10F, 3, false, true);
		format("rgb9_e5", GL30C.GL_RGB9_E5, 3, false, false);
		format("r8i", GL30C.GL_R8I, 1, true, true);
		format("r8ui", GL30C.GL_R8UI, 1, true, true);
		format("r16i", GL30C.GL_R16I, 1, true, true);
		format("r16ui", GL30C.GL_R16UI, 1, true, true);
		format("r32i", GL30C.GL_R32I, 1, true, true);
		format("r32ui", GL30C.GL_R32UI, 1, true, true);
		format("rg8i", GL30C.GL_RG8I, 2, true, true);
		format("rg8ui", GL30C.GL_RG8UI, 2, true, true);
		format("rg16i", GL30C.GL_RG16I, 2, true, true);
		format("rg16ui", GL30C.GL_RG16UI, 2, true, true);
		format("rg32i", GL30C.GL_RG32I, 2, true, true);
		format("rg32ui", GL30C.GL_RG32UI, 2, true, true);
		format("rgb8i", GL30C.GL_RGB8I, 3, true, false);
		format("rgb8ui", GL30C.GL_RGB8UI, 3, true, false);
		format("rgb16i", GL30C.GL_RGB16I, 3, true, false);
		format("rgb16ui", GL30C.GL_RGB16UI, 3, true, false);
		format("rgb32i", GL30C.GL_RGB32I, 3, true, false);
		format("rgb32ui", GL30C.GL_RGB32UI, 3, true, false);
		format("rgba8i", GL30C.GL_RGBA8I, 4, true, true);
		format("rgba8ui", GL30C.GL_RGBA8UI, 4, true, true);
		format("rgba16i", GL30C.GL_RGBA16I, 4, true, true);
		format("rgba16ui", GL30C.GL_RGBA16UI, 4, true, true);
		format("rgba32i", GL30C.GL_RGBA32I, 4, true, true);
		format("rgba32ui", GL30C.GL_RGBA32UI, 4, true, true);
		format("rgb10_a2ui", GL33C.GL_RGB10_A2UI, 4, true, true);
	}
}
