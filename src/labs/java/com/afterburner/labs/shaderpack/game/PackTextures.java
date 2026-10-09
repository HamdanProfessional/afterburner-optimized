package com.afterburner.labs.shaderpack.game;

import com.afterburner.labs.shaderpack.GlslTranslator;
import com.afterburner.labs.shaderpack.PackFiles;
import com.afterburner.labs.shaderpack.ShaderProperties;
import com.afterburner.labs.shaderpack.TranslateTarget;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Textures that aren't screen buffers: the pack's own (texture.&lt;stage&gt;.&lt;name&gt; in shaders.properties), the noise texture,
 * and stand-ins for what we don't render yet (the shadow map: nothing is in shadow).
 */
final class PackTextures implements AutoCloseable {
	record Bound(GpuTextureView view, GpuSampler sampler) {}

	private final List<GpuTexture> textures = new ArrayList<>();
	private final List<GpuTextureView> views = new ArrayList<>();
	/** Stage (gbuffers, deferred, composite, ...) to sampler name (as the translator names it) to texture. */
	private final Map<String, Map<String, Bound>> custom = new HashMap<>();
	final Bound white;
	final Bound black;
	/** A depth of 1 everywhere: every shadow lookup is lit. */
	final Bound noShadow;
	final Bound noise;
	/** The normal map packs read (normals): flat, no occlusion, no height. The specular one is {@link #black}: no shine, no glow. */
	final Bound flatNormals;

	PackTextures(PackFiles files, ShaderProperties properties, List<String> warnings) {
		GpuDevice device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();
		this.white = this.solid(encoder, "white", new Vector4f(1, 1, 1, 1));
		this.black = this.solid(encoder, "black", new Vector4f(0, 0, 0, 0));
		this.flatNormals = this.solid(encoder, "flat normals", new Vector4f(0.5f, 0.5f, 1, 1));
		GpuTexture shadow = device.createTexture("Afterburner no shadow", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT
			| GpuTexture.USAGE_COPY_DST, GpuFormat.D32_FLOAT, 1, 1, 1, 1);
		encoder.clearDepthTexture(shadow, 1.0);
		this.noShadow = new Bound(this.keep(shadow), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));

		Bound noise = null;
		for (ShaderProperties.Entry e : properties.withPrefix("texture.")) {
			String[] parts = e.key().split("\\.", 3);
			if (parts.length == 2 && parts[1].equals("noise")) {
				noise = this.load(files, e.value(), warnings);
				continue;
			}
			if (parts.length != 3) continue;
			Bound t = this.load(files, e.value(), warnings);
			if (t == null) continue;
			String sampler = GlslTranslator.samplerGlslName(GlslTranslator.canonicalSampler(parts[2], TranslateTarget.Kind.FULLSCREEN, false));
			this.custom.computeIfAbsent(parts[1], k -> new HashMap<>()).put(sampler, t);
		}
		this.noise = noise != null ? noise : this.randomNoise(encoder);
	}

	/** The pack's texture for a sampler in a stage, or null. */
	@Nullable Bound custom(String stage, String sampler) {
		Map<String, Bound> forStage = this.custom.get(stage);
		return forStage == null ? null : forStage.get(sampler);
	}

	Map<String, Bound> custom(String stage) {
		return this.custom.getOrDefault(stage, Map.of());
	}

	private Bound solid(CommandEncoder encoder, String name, Vector4f color) {
		GpuTexture t = RenderSystem.getDevice().createTexture("Afterburner " + name, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT
			| GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
		encoder.clearColorTexture(t, color);
		return new Bound(this.keep(t), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
	}

	/** OptiFine's default noisetex: 256x256 random colors. */
	private Bound randomNoise(CommandEncoder encoder) {
		Random random = new Random(0);
		try (NativeImage image = new NativeImage(256, 256, false)) {
			for (int y = 0; y < 256; y++) {
				for (int x = 0; x < 256; x++) image.setPixel(x, y, 0xFF000000 | random.nextInt(0x1000000));
			}
			return new Bound(this.upload(encoder, "noise", image), RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR));
		}
	}

	private @Nullable Bound load(PackFiles files, String path, List<String> warnings) {
		String file = "/" + path.strip().replace('\\', '/').replaceAll("^/+", "");
		try {
			byte[] bytes = files.readBytes(file);
			if (bytes == null) {
				warnings.add("Texture " + path + " not found");
				return null;
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
			try (NativeImage image = NativeImage.read(bytes)) {
				GpuTextureView view = this.upload(RenderSystem.getDevice().createCommandEncoder(), path, image);
				FilterMode filter = blur ? FilterMode.LINEAR : FilterMode.NEAREST;
				GpuSampler sampler = clamp ? RenderSystem.getSamplerCache().getClampToEdge(filter) : RenderSystem.getSamplerCache().getRepeat(filter);
				return new Bound(view, sampler);
			}
		} catch (Exception e) {
			warnings.add("Texture " + path + ": " + e);
			return null;
		}
	}

	private GpuTextureView upload(CommandEncoder encoder, String name, NativeImage image) {
		GpuFormat format = switch (image.format().components()) {
			case 1 -> GpuFormat.R8_UNORM;
			case 2 -> GpuFormat.RG8_UNORM;
			case 3 -> GpuFormat.RGB8_UNORM;
			default -> GpuFormat.RGBA8_UNORM;
		};
		GpuTexture t = RenderSystem.getDevice().createTexture("Afterburner " + name, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, format,
			image.getWidth(), image.getHeight(), 1, 1);
		encoder.writeToTexture(t, image);
		return this.keep(t);
	}

	private GpuTextureView keep(GpuTexture t) {
		GpuTextureView view = RenderSystem.getDevice().createTextureView(t);
		this.textures.add(t);
		this.views.add(view);
		return view;
	}

	@Override
	public void close() {
		for (GpuTextureView v : this.views) v.close();
		for (GpuTexture t : this.textures) t.close();
		this.views.clear();
		this.textures.clear();
	}
}
