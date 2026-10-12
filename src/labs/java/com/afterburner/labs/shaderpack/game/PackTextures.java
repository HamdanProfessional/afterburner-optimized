package com.afterburner.labs.shaderpack.game;

import com.afterburner.labs.shaderpack.GlslTranslator;
import com.afterburner.labs.shaderpack.PackFiles;
import com.afterburner.labs.shaderpack.PackProperties;
import com.afterburner.labs.shaderpack.TranslateTarget;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayInputStream;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Textures that aren't screen buffers: the pack's own (texture.&lt;stage&gt;.&lt;name&gt; in shaders.properties), the noise texture,
 * and stand-ins for what isn't drawn (no shadow map: nothing is in shadow). Each is an OpenGL texture name, its filtering
 * and wrapping set on it.
 */
final class PackTextures implements AutoCloseable {
	private final List<Integer> textures = new ArrayList<>();
	/** Stage (gbuffers, deferred, composite, ...) to sampler name (as the translator names it) to texture. */
	private final Map<String, Map<String, Integer>> custom = new HashMap<>();
	final int white;
	final int black;
	/** A depth of 1 everywhere: every shadow lookup is lit. */
	final int noShadow;
	final int noise;
	/** The normal map packs read (normals): flat, no occlusion, no height. The specular one is {@link #black}: no shine, no glow. */
	final int flatNormals;

	PackTextures(PackFiles files, PackProperties properties, List<String> warnings) {
		this.white = this.solid(0xFFFFFFFF);
		this.black = this.solid(0x00000000);
		this.flatNormals = this.solid(0xFFFF8080);
		this.noShadow = this.keep(Gl.texture(Gl.Format.DEPTH, 1, 1, true, false));
		Gl.clearDepth(this.noShadow, 1.0F);

		int noise = 0;
		for (PackProperties.Entry e : properties.withPrefix("texture.")) {
			String[] parts = e.key().split("\\.", 3);
			if (parts.length == 2 && parts[1].equals("noise")) {
				noise = this.load(files, e.value(), warnings);
				continue;
			}
			if (parts.length != 3) continue;
			int t = this.load(files, e.value(), warnings);
			if (t == 0) continue;
			String sampler = GlslTranslator.samplerGlslName(GlslTranslator.canonicalSampler(parts[2], TranslateTarget.Kind.FULLSCREEN, false));
			this.custom.computeIfAbsent(parts[1], k -> new HashMap<>()).put(sampler, t);
		}
		this.noise = noise != 0 ? noise : this.randomNoise();
	}

	/** The pack's texture for a sampler in a stage, or 0. */
	int custom(String stage, String sampler) {
		Map<String, Integer> forStage = this.custom.get(stage);
		Integer t = forStage == null ? null : forStage.get(sampler);
		return t == null ? 0 : t;
	}

	Map<String, Integer> custom(String stage) {
		return this.custom.getOrDefault(stage, Map.of());
	}

	/** A 1x1 texture of one color (RGBA bytes, red in the low one). */
	private int solid(int rgba) {
		IntBuffer pixel = MemoryUtil.memAllocInt(1);
		try {
			pixel.put(0, rgba);
			return this.keep(Gl.texture(1, 1, pixel, false, false));
		} finally {
			MemoryUtil.memFree(pixel);
		}
	}

	/** OptiFine's default noisetex: 256x256 random colors. */
	private int randomNoise() {
		Random random = new Random(0);
		IntBuffer pixels = MemoryUtil.memAllocInt(256 * 256);
		try {
			for (int i = 0; i < 256 * 256; i++) {
				int rgb = random.nextInt(0x1000000);
				// The ARGB color as RGBA bytes.
				pixels.put(i, 0xFF000000 | (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 0xFF));
			}
			return this.keep(Gl.texture(256, 256, pixels, true, true));
		} finally {
			MemoryUtil.memFree(pixels);
		}
	}

	/** The texture at a path in the pack, or 0. */
	private int load(PackFiles files, String path, List<String> warnings) {
		String file = "/" + path.strip().replace('\\', '/').replaceAll("^/+", "");
		try {
			byte[] bytes = files.readBytes(file);
			if (bytes == null) {
				warnings.add("Texture " + path + " not found");
				return 0;
			}
			boolean blur = false;
			boolean clamp = false;
			byte[] meta = files.readBytes(file + ".mcmeta");
			if (meta != null) {
				JsonElement json = JsonParser.parseString(new String(meta, StandardCharsets.UTF_8));
				if (json.isJsonObject() && json.getAsJsonObject().get("texture") instanceof JsonObject texture) {
					blur = texture.has("blur") && texture.get("blur").getAsBoolean();
					clamp = texture.has("clamp") && texture.get("clamp").getAsBoolean();
				}
			}
			try (NativeImage image = NativeImage.read(NativeImage.Format.RGBA, new ByteArrayInputStream(bytes))) {
				int w = image.getWidth(), h = image.getHeight();
				IntBuffer pixels = MemoryUtil.memAllocInt(w * h);
				try {
					for (int y = 0; y < h; y++) {
						for (int x = 0; x < w; x++) pixels.put(y * w + x, image.getPixelRGBA(x, y));
					}
					return this.keep(Gl.texture(w, h, pixels, blur, !clamp));
				} finally {
					MemoryUtil.memFree(pixels);
				}
			}
		} catch (Exception e) {
			warnings.add("Texture " + path + ": " + e);
			return 0;
		}
	}

	private int keep(int texture) {
		this.textures.add(texture);
		return texture;
	}

	@Override
	public void close() {
		for (int t : this.textures) Gl.deleteTexture(t);
		this.textures.clear();
	}
}
