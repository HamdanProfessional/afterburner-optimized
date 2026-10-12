package com.afterburner.labs.shaderpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The pack's own storage from shaders.properties, as Iris has it: custom images
 * ({@code image.NAME = sampler format internalFormat pixelType clear relative (width [height [depth]] | scaleX scaleY)}) and
 * storage buffers ({@code bufferObject.N = bytes [relative scaleX scaleY]}). Options can be in the values
 * ({@code ... COLORED_LIGHTING 64 COLORED_LIGHTING}); they're replaced with their values here.
 */
public final class PackImages {
	/**
	 * A custom image. {@code sampler} is the name the pack reads it by as a texture, null for "none"; formats are as written,
	 * lower case ("red_integer", "r16ui", "unsigned_int"). A relative image is {@code scaleX} by {@code scaleY} of the render
	 * size; otherwise {@code height} and {@code depth} are 0 for the dimensions it doesn't have.
	 */
	public record Image(String name, @Nullable String sampler, String format, String internalFormat, String pixelType, boolean clear,
			boolean relative, float scaleX, float scaleY, int width, int height, int depth) {
		/** 1, 2 or 3. */
		public int dimensions() {
			return this.relative ? 2 : this.depth > 0 ? 3 : this.height > 0 ? 2 : 1;
		}

		/** Whether it holds integers (r16ui, rgba32i, ...): read with usampler/isampler, never filtered. */
		public boolean integer() {
			return this.internalFormat.endsWith("ui") || this.internalFormat.endsWith("i");
		}

		/** Texels, for the memory warning. */
		public long texels(int renderWidth, int renderHeight) {
			if (this.relative) return (long) Math.max(1, (int) (renderWidth * this.scaleX)) * Math.max(1, (int) (renderHeight * this.scaleY));
			return (long) this.width * Math.max(1, this.height) * Math.max(1, this.depth);
		}
	}

	/** A storage buffer: {@code bytes} big, or {@code bytes} per pixel of a {@code scaleX} by {@code scaleY} part of the render size. */
	public record Buffer(int index, long bytes, boolean relative, float scaleX, float scaleY) {
		public long size(int renderWidth, int renderHeight) {
			if (!this.relative) return this.bytes;
			return this.bytes * Math.max(1, (int) (renderWidth * this.scaleX)) * Math.max(1, (int) (renderHeight * this.scaleY));
		}
	}

	public static final PackImages NONE = new PackImages(Map.of(), Map.of(), "default", List.of());

	/** By image name, in file order. */
	public final Map<String, Image> images;
	/** By index. */
	public final Map<Integer, Buffer> buffers;
	/** shadow.culling: "default", "true", "false" or "reversed" (pick what's around the camera too, for voxelizing). */
	public final String shadowCulling;
	public final List<String> warnings;

	private PackImages(Map<String, Image> images, Map<Integer, Buffer> buffers, String shadowCulling, List<String> warnings) {
		this.images = images;
		this.buffers = buffers;
		this.shadowCulling = shadowCulling;
		this.warnings = warnings;
	}

	public boolean isEmpty() {
		return this.images.isEmpty() && this.buffers.isEmpty();
	}

	/** The image a sampler name reads, or null. */
	public @Nullable Image bySampler(String sampler) {
		for (Image image : this.images.values()) if (sampler.equals(image.sampler)) return image;
		return null;
	}

	public static PackImages parse(PackProperties properties, Map<String, GlslPreprocessor.Macro> options) {
		List<String> warnings = new ArrayList<>();
		Map<String, Image> images = new LinkedHashMap<>();
		for (PackProperties.Entry e : properties.withPrefix("image.")) {
			String name = e.key().substring("image.".length()).strip();
			String[] v = expand(e.value(), options).strip().split("\\s+");
			try {
				if (name.isEmpty() || v.length < 7) throw new IllegalArgumentException("expected sampler format internalFormat pixelType clear relative size");
				String sampler = v[0].equalsIgnoreCase("none") ? null : v[0];
				boolean clear = Boolean.parseBoolean(v[4]);
				boolean relative = Boolean.parseBoolean(v[5]);
				Image image;
				if (relative) {
					if (v.length < 8) throw new IllegalArgumentException("a relative image needs scaleX and scaleY");
					image = new Image(name, sampler, lower(v[1]), lower(v[2]), lower(v[3]), clear, true, Float.parseFloat(v[6]),
						Float.parseFloat(v[7]), 0, 0, 0);
				} else {
					int width = Integer.parseInt(v[6]);
					int height = v.length > 7 ? Integer.parseInt(v[7]) : 0;
					int depth = v.length > 8 ? Integer.parseInt(v[8]) : 0;
					if (width <= 0 || height < 0 || depth < 0) throw new IllegalArgumentException("bad size");
					image = new Image(name, sampler, lower(v[1]), lower(v[2]), lower(v[3]), clear, false, 1, 1, width, height, depth);
				}
				images.put(name, image);
			} catch (IllegalArgumentException ex) {
				warnings.add(e.origin() + ": image." + name + ": " + ex.getMessage());
			}
		}

		Map<Integer, Buffer> buffers = new LinkedHashMap<>();
		for (PackProperties.Entry e : properties.withPrefix("bufferObject.")) {
			String[] v = expand(e.value(), options).strip().split("\\s+");
			try {
				int index = Integer.parseInt(e.key().substring("bufferObject.".length()).strip());
				if (index < 0 || index > 15) throw new IllegalArgumentException("index must be 0 to 15");
				long bytes = Long.parseLong(v[0]);
				if (bytes <= 0) throw new IllegalArgumentException("bad size");
				boolean relative = v.length >= 4 && Boolean.parseBoolean(v[1]);
				buffers.put(index, relative
					? new Buffer(index, bytes, true, Float.parseFloat(v[2]), Float.parseFloat(v[3]))
					: new Buffer(index, bytes, false, 1, 1));
			} catch (IllegalArgumentException ex) {
				warnings.add(e.origin() + ": " + e.key() + ": " + ex.getMessage());
			}
		}

		String culling = properties.get("shadow.culling", "default").strip().toLowerCase(Locale.ROOT);
		return new PackImages(images, buffers, culling, warnings);
	}

	private static String lower(String s) {
		return s.toLowerCase(Locale.ROOT);
	}

	/** Option macros in a value, replaced with their bodies (a few rounds, for options defined by other options). */
	static String expand(String value, Map<String, GlslPreprocessor.Macro> options) {
		String out = value;
		for (int round = 0; round < 4; round++) {
			StringBuilder next = new StringBuilder();
			boolean changed = false;
			for (GlslPreprocessor.Token t : GlslPreprocessor.tokenize(out)) {
				GlslPreprocessor.Macro macro = t.kind() == GlslPreprocessor.Kind.IDENT ? options.get(t.text()) : null;
				if (macro != null && macro.params == null && !macro.body().isBlank()) {
					next.append(macro.body().strip());
					changed = true;
				} else {
					next.append(t.text());
				}
			}
			out = next.toString();
			if (!changed) break;
		}
		return out;
	}
}
