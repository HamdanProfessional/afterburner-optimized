package com.afterburner.labs.shaderpack.game;

import com.afterburner.client.render.TerrainExtras;
import com.afterburner.labs.lod.Lod;
import com.afterburner.labs.mixin.shader.LevelRendererAccessor;
import com.afterburner.labs.shaderpack.PackFiles;
import com.afterburner.labs.shaderpack.PackLoader;
import com.afterburner.labs.shaderpack.PackMacros;
import com.afterburner.labs.shaderpack.PackOptions;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlUtil;
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
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
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
	/** What the pack drawing was loaded for: the same pack and dimension keeps drawing while it's loaded again. */
	private static String drawnFor = "";
	/** The pack being read and translated in the background ({@link #load}), if one is. */
	private static @Nullable CompletableFuture<Prepared> preparing;
	/** Fabulous graphics are left out this frame: set at the frame's start, so a pack failing mid-frame doesn't bring them back half-way. */
	private static boolean hideFabulous;

	/**
	 * What the sky is drawing where its shader doesn't tell (the sky, the stars and the dark disc under the horizon share one;
	 * so do the sun and the moon), as the render stage it is.
	 */
	public enum SkyStage {
		NONE("NONE"),
		SKY("SKY"),
		STARS("STARS"),
		SUNSET("SUNSET"),
		SUN("SUN"),
		MOON("MOON"),
		CUSTOM("CUSTOM_SKY"),
		VOID("VOID");

		final String stage;

		SkyStage(String stage) {
			this.stage = stage;
		}
	}

	/** The part of the frame the game draws now, from its profiler sections: what some of its shaders draw depends on it. */
	public enum Phase {
		NONE,
		SKY,
		TERRAIN,
		ENTITIES,
		BLOCK_ENTITIES,
		TRANSLUCENT,
		PARTICLES,
		CLOUDS,
		WEATHER,
		HAND;

		/** The phase a profiler section of the world's drawing starts, or null for one that doesn't change it. */
		static @Nullable Phase of(String section) {
			return switch (section) {
				case "sky" -> SKY;
				case "fog", "terrain_setup", "compile_sections", "terrain" -> TERRAIN;
				case "entities" -> ENTITIES;
				case "blockentities", "destroyProgress", "outline" -> BLOCK_ENTITIES;
				case "translucent", "string" -> TRANSLUCENT;
				case "particles" -> PARTICLES;
				case "clouds" -> CLOUDS;
				case "weather" -> WEATHER;
				default -> null;
			};
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
		return macros(GlUtil.getVendor(), GlUtil.getRenderer(), mc.options.mipmapLevels().get(), PackStorage.supported(), farTerrain);
	}

	/** {@link #macros(Minecraft, boolean)} from the driver's names, read on the render thread beforehand. */
	private static Map<String, String> macros(String vendor, String gpu, int mipmaps, boolean storage, boolean farTerrain) {
		Map<String, String> macros = PackMacros.of(vendor, gpu, mipmaps);
		if (storage) PackMacros.addIris(macros);
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
		// While a pack loads in the background ({@link #load}) the game draws the world its own way.
		return renderer != null;
	}

	/** What to load: the pack and the dimension, and whether the far terrain is on (its macros); "" for nothing. */
	private static String key(Minecraft mc) {
		if (mc.level == null || selected.isEmpty()) return "";
		return selected + "|" + mc.level.dimension().location() + (Lod.forPacks() ? "|far" : "");
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

	/** A pack read and translated off the render thread, its files still open for its textures; or why it couldn't be. */
	private record Prepared(String key, String pack, String dimension, @Nullable PackFiles files, PackLoader.@Nullable Loaded loaded,
			@Nullable Throwable error, long started, long readNanos) {
		void discard() {
			closeQuietly(files);
		}
	}

	private static void closeQuietly(@Nullable PackFiles files) {
		if (files == null) return;
		try {
			files.close();
		} catch (Exception ignored) {
		}
	}

	/**
	 * Starts loading the chosen pack. Reading its files and preprocessing and translating its programs (most of a load:
	 * seconds for a big pack) is done on a background thread while the game draws on; only making its programs and
	 * textures, which needs OpenGL, is left for the render thread ({@link #takePrepared}). A pack whose options changed keeps
	 * drawing until it's loaded again with them.
	 */
	private static void load(Minecraft mc, String key, String dimension) {
		cancelPreparing();
		if (FabricLoader.getInstance().isModLoaded("iris") || FabricLoader.getInstance().isModLoaded("sodium")) {
			close();
			message(mc, "Afterburner's shader packs are off while Sodium or Iris is installed (use Iris's shader packs instead)");
			return;
		}
		if (renderer != null && !sameDrawing(key)) close();
		String pack = selected;
		Path path = folder().resolve(pack);
		// What needs OpenGL is asked here, on the render thread (the background thread has no context).
		String vendor = GlUtil.getVendor(), gpu = GlUtil.getRenderer();
		boolean storage = PackStorage.supported();
		int mipmaps = mc.options.mipmapLevels().get();
		long start = System.nanoTime();
		preparing = CompletableFuture.supplyAsync(() -> {
			PackFiles files = null;
			try {
				files = PackFiles.open(path);
				Map<String, String> macros = macros(vendor, gpu, mipmaps, storage, farTerrain(files));
				// The player's choice of the pack's options: their lines are rewritten as the files are read.
				Map<String, String> chosen = PackOptions.readValues(optionsFile(pack));
				if (!chosen.isEmpty()) files.rewriteWith(PackOptions.read(files, macros).rewriter(chosen));
				PackLoader.Loaded loaded = PackLoader.load(files, dimension, macros, PackMacros.addBiomes(new HashMap<>(), PackBiomes.names()));
				return new Prepared(key, pack, dimension, files, loaded, null, start, System.nanoTime() - start);
			} catch (Exception | LinkageError e) {
				closeQuietly(files);
				return new Prepared(key, pack, dimension, null, null, e, start, 0);
			}
		}, Util.backgroundExecutor());
	}

	/** Whether the pack drawing is the one being loaded, for the same dimension (only its options changed). */
	private static boolean sameDrawing(String key) {
		return key.equals(drawnFor);
	}

	/** A load started for another choice than now: its files are closed when it's done. */
	private static void cancelPreparing() {
		CompletableFuture<Prepared> old = preparing;
		preparing = null;
		if (old != null) old.thenAccept(Prepared::discard);
	}

	/** On the render thread: a pack done preparing becomes the one drawing. */
	private static void takePrepared(Minecraft mc) {
		CompletableFuture<Prepared> ready = preparing;
		if (ready == null || !ready.isDone()) return;
		preparing = null;
		Prepared p = ready.join();
		if (!p.key.equals(loadedFor)) {
			p.discard();
			return;
		}
		close();
		if (p.error != null) {
			LOGGER.error("[Afterburner] Couldn't load shader pack {}", p.pack, p.error);
			message(mc, "Couldn't load shader pack " + p.pack + ": " + p.error.getMessage());
			return;
		}
		long made = System.nanoTime();
		try {
			List<String> warnings = new ArrayList<>(p.loaded.warnings);
			renderer = new PackRenderer(p.pack, p.loaded, p.files, warnings);
			blockIds = BlockIdTable.of(p.loaded.blockIds);
			drawnFor = p.key;
			for (String w : warnings) LOGGER.warn("[Afterburner] {}: {}", p.pack, w);
			LOGGER.info("[Afterburner] Shader pack {} loaded for {} in {} ms ({} ms read in the background, {} ms made on the render thread)",
					p.pack, p.dimension, (System.nanoTime() - p.started) / 1_000_000, p.readNanos / 1_000_000, (System.nanoTime() - made) / 1_000_000);
		} catch (Exception | LinkageError e) {
			LOGGER.error("[Afterburner] Couldn't load shader pack {}", p.pack, e);
			close();
			message(mc, "Couldn't load shader pack " + p.pack + ": " + e.getMessage());
		} finally {
			p.discard();
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
			mc.gui.getChat().addMessage(Component.literal("[Afterburner] " + text));
		} catch (RuntimeException e) {
			// No chat yet.
		}
	}

	// ---- Hooks ----

	/**
	 * In place of the game's clear before the world: loads or drops the pack as the choice and dimension say, and starts its
	 * frame. {@code view} and {@code projection} are what the world is drawn with. Whether the pack cleared (the game doesn't).
	 */
	public static boolean beginLevel(Minecraft mc, DeltaTracker delta, Matrix4fc view, Matrix4fc projection) {
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
			if (key.isEmpty()) {
				cancelPreparing();
				close();
			} else {
				load(mc, key, mc.level.dimension().location().toString());
			}
		}
		takePrepared(mc);
		// Terrain is built again for the pack (normals, block IDs), or back the game's way.
		boolean hid = hideFabulous;
		hideFabulous = renderer != null;
		TerrainExtras.use(renderer != null ? blockIds : null);
		// Their buffers dropped while the pack was on (the world loaded again): made again.
		if (hid && !hideFabulous && Minecraft.useShaderTransparency() && mc.levelRenderer.getTranslucentTarget() == null) {
			((LevelRendererAccessor) mc.levelRenderer).afterburner$initTransparency();
		}
		PackRenderer r = renderer;
		if (r == null) return false;
		try {
			r.beginFrame(mc, delta, view, projection);
			return true;
		} catch (RuntimeException e) {
			fail("the frame's start", e);
			return false;
		}
	}

	/** A profiler section of the world's drawing started. */
	public static void section(String name) {
		PackRenderer r = renderer;
		if (r == null || !r.frameStarted()) return;
		Phase phase = Phase.of(name);
		if (phase == null) return;
		try {
			r.phase(phase);
		} catch (RuntimeException e) {
			fail(phase == Phase.TRANSLUCENT ? "deferred" : "the world's " + name, e);
		}
	}

	/** Set by the sky's drawing (see the sky stage mixin) around what its shaders don't tell apart. */
	public static void skyStage(SkyStage stage) {
		PackRenderer r = renderer;
		if (r != null) r.sky(stage);
	}

	/**
	 * The game binds framebuffer {@code id} to {@code target}: whether the pack bound its own instead (its main one, while the
	 * world is drawn), and the game's bind is left out.
	 */
	public static boolean bindFramebuffer(int target, int id) {
		PackRenderer r = renderer;
		if (r == null) return false;
		try {
			return r.redirect(target, id);
		} catch (RuntimeException e) {
			fail("a world pass", e);
			return false;
		}
	}

	/** The game bound one of its targets (and maybe set the viewport for it). */
	public static void bound(RenderTarget target) {
		PackRenderer r = renderer;
		if (r != null) r.bound(target);
	}

	/** Before the game applies or clears one of its shaders: what {@link #applied} changed is put back. */
	public static void applying() {
		PackRenderer.unapply();
	}

	/** The game applied one of its shaders: in the pack's buffers, the pack's program draws instead. */
	public static void applied(ShaderInstance shader) {
		PackRenderer r = renderer;
		if (r == null) return;
		try {
			r.applied(shader);
		} catch (RuntimeException e) {
			fail("a shader", e);
		}
	}

	/** The far terrain was prepared for the frame: the pack draws it, before the game's terrain. */
	public static void farTerrain() {
		PackRenderer r = renderer;
		if (r == null) return;
		try {
			r.drawFarTerrain();
		} catch (RuntimeException e) {
			fail("the far terrain", e);
		}
	}

	/** Before the hand, in place of the game's depth clear (the pack's hand is drawn in front): whether the clear is left out. */
	public static boolean beforeHand() {
		PackRenderer r = renderer;
		if (r == null || !r.frameStarted()) return false;
		try {
			r.phase(Phase.HAND);
			return true;
		} catch (RuntimeException e) {
			fail("the hand", e);
			return false;
		}
	}

	/** After the hand (or the world, without one): composite and final. */
	public static void finish(Minecraft mc) {
		PackRenderer r = renderer;
		if (r == null || !r.frameStarted()) return;
		try {
			r.finish(mc);
		} catch (RuntimeException e) {
			fail("composite", e);
		}
	}

	/**
	 * Whether the game's Fabulous graphics are left out (its own buffers for translucents, particles and weather, composited
	 * after): a pack draws all of them into its buffers.
	 */
	public static boolean hidesFabulous() {
		return hideFabulous;
	}

	/**
	 * Whether the view bobbing goes into the world's view rather than its projection, as packs (and Iris) have it: their
	 * passes take positions back out of the depth with the projection.
	 */
	public static boolean movesBob(Minecraft mc) {
		return drawsWorld(mc);
	}
}
