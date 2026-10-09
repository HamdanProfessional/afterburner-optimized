package com.afterburner.labs.shaderpack.game;

import com.afterburner.labs.lod.Lod;
import com.afterburner.client.render.TerrainExtras;
import com.afterburner.labs.shaderpack.PackFiles;
import com.afterburner.labs.shaderpack.PackLoader;
import com.afterburner.labs.shaderpack.PackOptions;
import com.afterburner.labs.shaderpack.PackMacros;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which shader pack is on, and the hooks the mixins call. The choices (pack, upscaling) are kept in
 * config/afterburner-shaders.properties; packs
 * are the zips and folders in .minecraft/shaderpacks. A pack is loaded for the world's dimension and again when that changes.
 * <p>
 * Anything going wrong turns the pack off (with a chat line) rather than taking the game down.
 */
public final class Shaderpacks {
	private static final Logger LOGGER = LoggerFactory.getLogger("afterburner");
	private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("afterburner-shaders.properties");
	private static final Map<CompiledRenderPipeline, RenderPipeline> SOURCES = new Reference2ReferenceOpenHashMap<>();

	/**
	 * FSR 1's quality modes: how much of the screen's width and height the world is drawn at, before {@link Upscaler} scales it
	 * up. Only while a pack is on.
	 */
	public enum Upscale {
		OFF("off", 1.0F),
		ULTRA_QUALITY("ultra_quality", 1.0F / 1.3F),
		QUALITY("quality", 1.0F / 1.5F),
		BALANCED("balanced", 1.0F / 1.7F),
		PERFORMANCE("performance", 0.5F);

		public final String id;
		public final float scale;

		Upscale(String id, float scale) {
			this.id = id;
			this.scale = scale;
		}

		static Upscale of(String id) {
			for (Upscale u : values()) if (u.id.equals(id)) return u;
			return OFF;
		}
	}

	/**
	 * How often the shadow map is drawn again. Kept, it's only drawn when what it shows changed: the terrain in it, the sun's
	 * angle (moved in steps of a tenth of a degree, then), or the camera by the pack's shadowIntervalSize.
	 */
	public enum ShadowUpdates {
		/**
		 * When something changed; with packs that wave leaves and grass in their shadows, those are drawn again every frame over
		 * the solid blocks kept.
		 */
		SMART("smart"),
		/** Only when something changed: waving leaves' shadows stand still. */
		WHEN_NEEDED("when_needed"),
		/** Every frame, as other shader loaders do. */
		EVERY_FRAME("every_frame");

		public final String id;

		ShadowUpdates(String id) {
			this.id = id;
		}

		static ShadowUpdates of(String id) {
			for (ShadowUpdates u : values()) if (u.id.equals(id)) return u;
			return SMART;
		}
	}

	private static final Properties SAVED = readConfig();
	private static String selected = SAVED.getProperty("pack", "").strip();
	private static Upscale upscale = Upscale.of(SAVED.getProperty("upscale", "off").strip());
	private static ShadowUpdates shadowUpdates = ShadowUpdates.of(SAVED.getProperty("shadowUpdates", "smart").strip());
	private static @Nullable PackRenderer renderer;
	/** The pack's block IDs over all block states (see {@link TerrainExtras}), while it's loaded. */
	private static int @Nullable [] blockIds;
	/** A pack turned off mid-frame: closed at the next frame, when nothing uses its buffers. */
	private static @Nullable PackRenderer retired;
	/** What is loaded (or failed to load, so it isn't tried every frame): pack and dimension. */
	private static String loadedFor = "";
	private static PackPass.@Nullable Kind pendingKind;
	private static SkyStage skyStage = SkyStage.NONE;

	/**
	 * What the sky is drawing where its pipeline doesn't tell (the sun, the moon and the End's flash share one; so do the sky
	 * and the dark disc under the horizon), as the render stage it is.
	 */
	public enum SkyStage {
		NONE("NONE"),
		SUN("SUN"),
		MOON("MOON"),
		CUSTOM("CUSTOM_SKY"),
		VOID("VOID");

		final String stage;

		SkyStage(String stage) {
			this.stage = stage;
		}
	}

	private Shaderpacks() {
	}

	// ---- Choosing a pack ----

	public static Path folder() {
		return FabricLoader.getInstance().getGameDir().resolve("shaderpacks");
	}

	/** The packs in the shaderpacks folder: zips, and folders with a shaders folder in them. */
	public static List<String> available() {
		List<String> out = new ArrayList<>();
		try (Stream<Path> files = Files.list(folder())) {
			files.sorted().forEach(p -> {
				String name = p.getFileName().toString();
				if (name.toLowerCase().endsWith(".zip") || Files.isDirectory(p.resolve("shaders"))) out.add(name);
			});
		} catch (IOException e) {
			// No folder: no packs.
		}
		return out;
	}

	/** The chosen pack's file name, or "" for none. */
	public static String selected() {
		return selected;
	}

	public static void select(String name) {
		selected = name;
		// Never a key: the next frame loads the pack, or drops the one loaded ("" is the key for none).
		loadedFor = "\0";
		save();
	}

	/** Loads the chosen pack again (after its options changed). */
	public static void reload() {
		loadedFor = "\0";
	}

	/** Where the player's values of a pack's options are kept, as OptiFine and Iris keep them. */
	public static Path optionsFile(String pack) {
		return folder().resolve(pack + ".txt");
	}

	/** A pack's options and settings screens. */
	public static PackOptions options(String pack) throws IOException {
		try (PackFiles files = PackFiles.open(folder().resolve(pack))) {
			return PackOptions.read(files, macros(Minecraft.getInstance(), farTerrain(files)));
		}
	}

	/** {@code farTerrain}: with Distant Horizons' macros, for the pack to draw Afterburner's far terrain. */
	private static Map<String, String> macros(Minecraft mc, boolean farTerrain) {
		DeviceInfo device = RenderSystem.getDevice().getDeviceInfo();
		Map<String, String> macros = PackMacros.of(device.vendorName(), device.name(), mc.options.mipmapLevels().get());
		if (device.backendName().equals("OpenGL") && PackStorage.supported()) PackMacros.addIris(macros);
		if (farTerrain) PackMacros.addFarTerrain(macros);
		PackMacros.addBiomes(macros, PackBiomes.names());
		return macros;
	}

	/**
	 * Whether the pack draws the far terrain: it's on, and the pack has Distant Horizons' terrain program. A pack without one
	 * doesn't get its macros, or it would take its paths for land that's never drawn (MakeUp fades its own terrain out at the
	 * edge for it).
	 */
	private static boolean farTerrain(PackFiles files) throws IOException {
		if (!Lod.forPacks()) return false;
		for (String file : files.list()) {
			if (file.endsWith("/dh_terrain.fsh") || file.endsWith("/dh_terrain.vsh")) return true;
		}
		return false;
	}

	public static Upscale upscale() {
		return upscale;
	}

	/** Takes effect at the next frame. */
	public static void setUpscale(Upscale value) {
		upscale = value;
		save();
	}

	public static ShadowUpdates shadowUpdates() {
		return shadowUpdates;
	}

	/** Takes effect at the next frame. */
	public static void setShadowUpdates(ShadowUpdates value) {
		shadowUpdates = value;
		save();
	}

	private static void save() {
		Properties p = new Properties();
		p.setProperty("pack", selected);
		p.setProperty("upscale", upscale.id);
		p.setProperty("shadowUpdates", shadowUpdates.id);
		try (Writer out = Files.newBufferedWriter(CONFIG, StandardCharsets.UTF_8)) {
			p.store(out, "Afterburner shader pack: pack is a file name in .minecraft/shaderpacks, or empty for none; "
				+ "upscale is off, ultra_quality, quality, balanced or performance (FSR 1); "
				+ "shadowUpdates is smart, when_needed or every_frame");
		} catch (IOException e) {
			LOGGER.warn("[Afterburner] Couldn't save {}: {}", CONFIG, e.toString());
		}
	}

	private static Properties readConfig() {
		Properties p = new Properties();
		if (!Files.exists(CONFIG)) return p;
		try (Reader in = Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8)) {
			p.load(in);
		} catch (IOException | IllegalArgumentException e) {
			return new Properties();
		}
		return p;
	}

	/** Whether a pack is drawing the world. */
	public static boolean active() {
		return renderer != null;
	}

	/** Whether a pack draws the world this frame: it's loaded, or about to be (see {@link #beginFrame}). */
	public static boolean drawsWorld(Minecraft mc) {
		if (renderer != null) return true;
		String key = key(mc);
		return !key.isEmpty() && !key.equals(loadedFor);
	}

	/** What to load: the pack and the dimension, and whether the far terrain is on (its macros); "" for nothing. */
	private static String key(Minecraft mc) {
		if (mc.level == null || selected.isEmpty()) return "";
		return selected + "|" + mc.level.dimension().identifier() + (Lod.forPacks() ? "|far" : "");
	}

	/** Whether the pack draws Afterburner's far terrain (with its dh_terrain program). */
	public static boolean drawsFarTerrain() {
		PackRenderer r = renderer;
		return r != null && r.drawsFarTerrain();
	}

	/** The projection the pack draws the far terrain with this frame (its dhProjection, OpenGL's depth), or null without a pack. */
	public static @Nullable Matrix4fc farTerrainProjection() {
		PackRenderer r = renderer;
		return r != null ? r.farTerrainProjection() : null;
	}

	// ---- Loading ----

	private static void load(Minecraft mc, String dimension) {
		close();
		if (FabricLoader.getInstance().isModLoaded("iris") || FabricLoader.getInstance().isModLoaded("sodium")) {
			message(mc, "Afterburner's shader packs are off while Sodium or Iris is installed (use Iris's shader packs instead)");
			return;
		}
		DeviceInfo device = RenderSystem.getDevice().getDeviceInfo();
		if (!device.backendName().equals("OpenGL")) {
			message(mc, "Shader packs need the OpenGL renderer for now (this is " + device.backendName() + ")");
			return;
		}
		Path path = folder().resolve(selected);
		long start = System.nanoTime();
		try (PackFiles files = PackFiles.open(path)) {
			Map<String, String> macros = macros(mc, farTerrain(files));
			// The player's choice of the pack's options: their lines are rewritten as the files are read.
			Map<String, String> chosen = PackOptions.readValues(optionsFile(selected));
			if (!chosen.isEmpty()) files.rewriteWith(PackOptions.read(files, macros).rewriter(chosen));
			PackLoader.Loaded loaded = PackLoader.load(files, dimension, macros, PackMacros.addBiomes(new HashMap<>(), PackBiomes.names()));
			List<String> warnings = new ArrayList<>(loaded.warnings);
			renderer = new PackRenderer(selected, loaded, files, warnings);
			blockIds = BlockIdTable.of(loaded.blockIds);
			for (String w : warnings) LOGGER.warn("[Afterburner] {}: {}", selected, w);
			LOGGER.info("[Afterburner] Shader pack {} loaded for {} in {} ms", selected, dimension, (System.nanoTime() - start) / 1_000_000);
		} catch (Exception | LinkageError e) {
			LOGGER.error("[Afterburner] Couldn't load shader pack {}", selected, e);
			close();
			message(mc, "Couldn't load shader pack " + selected + ": " + e.getMessage());
		}
	}

	private static void close() {
		if (renderer != null) {
			try {
				renderer.close();
			} catch (RuntimeException e) {
				LOGGER.warn("[Afterburner] Closing shader pack", e);
			}
			renderer = null;
		}
		blockIds = null;
	}

	private static void fail(String where, Throwable t) {
		LOGGER.error("[Afterburner] Shader pack {} failed in {}, turning it off", selected, where, t);
		if (retired == null) retired = renderer;
		else close();
		renderer = null;
		blockIds = null;
		message(Minecraft.getInstance(), "Shader pack " + selected + " stopped working (" + t + "), it's off until you choose it again");
	}

	private static void message(Minecraft mc, String text) {
		try {
			mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[Afterburner] " + text));
		} catch (RuntimeException e) {
			// No chat yet.
		}
	}

	// ---- Hooks ----

	/** Before the world is drawn: loads or drops the pack as the choice and dimension say, and starts its frame. */
	public static void beginFrame(Minecraft mc, CameraRenderState camera, Matrix4fc projection) {
		if (retired != null) {
			PackRenderer old = retired;
			retired = null;
			try {
				old.close();
			} catch (RuntimeException e) {
				LOGGER.warn("[Afterburner] Closing shader pack", e);
			}
		}
		String key = key(mc);
		if (!key.equals(loadedFor)) {
			loadedFor = key;
			if (key.isEmpty()) close();
			else load(mc, mc.level.dimension().identifier().toString());
		}
		// Terrain is built again for the pack (normals, block IDs), or back the game's way.
		TerrainExtras.use(renderer != null ? blockIds : null);
		PackRenderer r = renderer;
		if (r == null) return;
		try {
			r.beginFrame(mc, camera, projection);
		} catch (RuntimeException e) {
			fail("the frame's start", e);
		}
	}

	/** createRenderPass: the world's passes go to the pack's buffers. */
	public static RenderPassDescriptor redirect(RenderPassDescriptor descriptor) {
		pendingKind = null;
		PackRenderer r = renderer;
		if (r == null || !r.frameStarted() || descriptor.colorAttachments().isEmpty()) return descriptor;
		var first = descriptor.colorAttachments().getFirst();
		if (first == null) return descriptor;
		var mainColor = Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView();
		// A pass on anything else stays as it is, the main pass started again too (see PackRenderer#beforeTranslucents and
		// OcclusionPortable): it's on our buffers already, maybe not those it began on.
		if (first.textureView() != mainColor) return descriptor;
		PackPass.Kind kind = switch (descriptor.label().get()) {
			case "Sky" -> PackPass.Kind.SKY;
			case "Main" -> PackPass.Kind.WORLD;
			case "Item in hand" -> PackPass.Kind.HAND;
			default -> null;
		};
		if (kind == null) return descriptor;
		try {
			RenderPassDescriptor redirected = r.redirect(descriptor, kind);
			pendingKind = kind;
			return redirected;
		} catch (RuntimeException e) {
			fail("a world pass", e);
			return descriptor;
		}
	}

	/** The pass {@link #redirect} changed was made. */
	public static void opened(RenderPass pass) {
		PackPass.Kind kind = pendingKind;
		pendingKind = null;
		if (kind == null || !(pass instanceof PackPass packPass)) return;
		packPass.afterburner$setPackKind(kind);
		PackRenderer r = renderer;
		if (r != null) r.bindWorld(pass, kind);
	}

	/**
	 * setPipeline in a pack pass: the pipeline to use instead; the same one if it's already ours; null to draw nothing (no
	 * program for it, or the pack was turned off mid-frame).
	 */
	public static @Nullable CompiledRenderPipeline pipelineFor(CompiledRenderPipeline pipeline, PackPass.Kind kind) {
		PackRenderer r = renderer;
		if (r == null) return null;
		if (r.pipelines.isOurs(pipeline)) return pipeline;
		RenderPipeline source = SOURCES.get(pipeline);
		if (source == null) return null;
		try {
			return r.pipelines.world(source, kind);
		} catch (RuntimeException e) {
			fail("a pipeline", e);
			return null;
		}
	}

	/** Set by the sky's drawing (see the sky stage mixin) around what its pipelines don't tell apart. */
	public static void skyStage(SkyStage stage) {
		skyStage = stage;
	}

	/** {@link #pipelineFor} gave a pack pass another pipeline, now set: the draws' render stage. */
	public static void drawStage(RenderPass pass, CompiledRenderPipeline pipeline, PackPass.Kind kind) {
		PackRenderer r = renderer;
		if (r == null) return;
		try {
			r.bindStage(pass, SOURCES.get(pipeline), kind, skyStage);
		} catch (RuntimeException e) {
			fail("a draw's render stage", e);
		}
	}

	/**
	 * setUniform of a texture in a pack pass: the sampler to use instead. The game binds its textures (the block atlas,
	 * entities') with smooth filtering and its own shaders pick the texels themselves; packs sample them directly, as OptiFine
	 * binds them: sharp, the atlas's mipmaps blended (GL_NEAREST_MIPMAP_LINEAR). Only the game's texture (Sampler0) and overlay
	 * (Sampler1): the lightmap (Sampler2) stays smooth, and so do the pack's own buffers (colortex, shadowcolor, custom textures).
	 */
	public static @Nullable GpuSampler sampler(String name, @Nullable GpuSampler sampler) {
		if (sampler == null || sampler.getMagFilter() != FilterMode.LINEAR || !(name.equals("Sampler0") || name.equals("Sampler1"))) return sampler;
		return RenderSystem.getSamplerCache().getSampler(sampler.getAddressModeU(), sampler.getAddressModeV(), FilterMode.NEAREST, FilterMode.NEAREST,
			sampler.getMaxLod().isEmpty());
	}

	/** The game compiled a pipeline: remembered, to know what a compiled one draws. */
	public static void remember(Object pipeline, Object compiled) {
		if (pipeline instanceof RenderPipeline p && compiled instanceof CompiledRenderPipeline c) SOURCES.put(c, p);
	}

	public static void forgetPipelines() {
		SOURCES.clear();
	}

	/** Before translucents in the main pass. */
	public static void beforeTranslucents(RenderPass main) {
		PackRenderer r = renderer;
		if (r == null || !(main instanceof PackPass p) || p.afterburner$packKind() != PackPass.Kind.WORLD) return;
		try {
			r.beforeTranslucents(main);
		} catch (RuntimeException e) {
			fail("deferred", e);
		}
	}

	/** How far from the camera entities cast the pack's shadows, in blocks: 0 without a pack or its shadow map. */
	public static double entityShadowRange() {
		PackRenderer r = renderer;
		return r != null ? r.entityShadowRange() : 0.0;
	}

	/** The game prepared its entity draws for the frame, before drawing any: their shadows (see ShadowMap#renderEntities). */
	public static void entityShadows(FeatureRenderDispatcher.PreparedFrame features) {
		PackRenderer r = renderer;
		if (r == null) return;
		try {
			r.entityShadows(features);
		} catch (RuntimeException e) {
			fail("entity shadows", e);
		}
	}

	/** After the hand: composite and final. */
	public static void afterHand(Minecraft mc) {
		PackRenderer r = renderer;
		if (r == null) return;
		try {
			r.finish(mc);
		} catch (RuntimeException e) {
			fail("composite", e);
		}
	}
}
