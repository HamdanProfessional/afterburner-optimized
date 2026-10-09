package com.afterburner.labs.lod;

import com.afterburner.labs.LabsFeatures;
import com.afterburner.labs.mixin.lod.ChunkSectionsAccessor;
import com.afterburner.labs.shaderpack.game.Shaderpacks;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Far terrain: the land past the render distance, drawn as simple colored blocks from the chunks seen before. Every
 * chunk the game lets go of is copied ({@link LodSnapshot}) and turned into coarser and coarser columns on a background
 * thread ({@link LodWorker}), kept on disk per world and dimension ({@link LodWorld}), and drawn in the main pass after
 * the solid terrain ({@link LodRenderer}). The render distance fog moves out to the far terrain's edge.
 * <p>
 * With a shader pack that has Distant Horizons' programs, the pack draws it with its dh_terrain, as it would Distant Horizons'
 * (see PackRenderer#drawFarTerrain), and does its own fog.
 * <p>
 * Our own design: it borrows the idea (keep what was seen, simplify it level by level) from Voxy and Distant Horizons,
 * none of their code, and needs no other mod.
 */
public final class Lod {
	private static final Logger LOGGER = LoggerFactory.getLogger("afterburner");
	private static @Nullable LodWorker worker;
	private static @Nullable LodWorld world;
	private static @Nullable ClientLevel worldLevel;
	private static volatile @Nullable LodRenderer renderer;
	/** Fog (water, the Nether, blindness) hides everything past the game's own chunks. */
	private static boolean fogHides;
	private static boolean prepared, failed;
	/** This frame's far terrain is drawn by the shader pack, with the chunks' view matrix. */
	private static boolean throughPack;
	private static @Nullable GpuBufferSlice terrainUniform;
	/** The water left for the shader pack's pass for it ({@link #drawForPack} drew the rest). */
	private static boolean waterPending;
	private static boolean unseenLand = LodSettings.unseenLand();

	private Lod() {
	}

	public static void init() {
		if (!LabsFeatures.FAR_TERRAIN) return;
		ClientChunkEvents.CHUNK_UNLOAD.register(Lod::chunkUnloaded);
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> stop());
	}

	/** Whether chunks are kept for the far terrain: it's on and has a distance. */
	private static boolean recording() {
		return LabsFeatures.FAR_TERRAIN && !failed && LodSettings.distance() > 0;
	}

	/** Whether shader packs are loaded to draw the far terrain (with Distant Horizons' macros). */
	public static boolean forPacks() {
		return recording();
	}

	/** Whether the far terrain can be drawn: it's on, and no shader pack is, or one that draws it. */
	public static boolean available() {
		return recording() && (!Shaderpacks.active() || Shaderpacks.drawsFarTerrain());
	}

	/** How far the far terrain a shader pack draws reaches this frame, in blocks; 0 for none. */
	public static float packDistance() {
		return Shaderpacks.active() && active() && !fogHides ? blocks() : 0.0F;
	}

	/** Whether the far terrain is drawn (not while a shader pack without it is on, or while {@link LodSettings#drawn} is 0). */
	public static boolean active() {
		return available() && LodSettings.drawn() > 0 && Minecraft.getInstance().level != null;
	}

	private static float blocks() {
		return LodSettings.drawn() * 16.0F;
	}

	private static LodWorker worker() {
		if (worker == null) worker = new LodWorker();
		return worker;
	}

	private static LodWorld world(ClientLevel level) {
		if (level == worldLevel && world != null) return world;
		closeWorld();
		Minecraft mc = Minecraft.getInstance();
		Identifier dimension = level.dimension().identifier();
		Path folder = mc.gameDirectory.toPath().resolve("afterburner").resolve("lod").resolve(worldName(mc))
				.resolve(clean(dimension.getNamespace() + "_" + dimension.getPath()));
		world = new LodWorld(folder, level.getMinY(), level.getHeight(), level.dimensionType().hasSkyLight(),
				level.registryAccess().lookupOrThrow(Registries.BIOME));
		world.generator = LodGenerator.create(worker(), world, level);
		worldLevel = level;
		return world;
	}

	/** The save's folder name in single player, the server's address in multiplayer. */
	private static String worldName(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			Path name = root.getFileName();
			return "sp-" + clean(name != null ? name.toString() : "world");
		}
		ServerData data = mc.getCurrentServer();
		if (data != null) return "mp-" + clean(data.ip);
		return "unknown";
	}

	private static String clean(String name) {
		StringBuilder out = new StringBuilder(name.length());
		for (int i = 0; i < name.length() && out.length() < 80; i++) {
			char c = name.charAt(i);
			out.append(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '.' ? c : '_');
		}
		return out.isEmpty() ? "_" : out.toString();
	}

	private static void closeWorld() {
		if (renderer != null) renderer.close();
		renderer = null;
		LodGenerator generator = world != null ? world.generator : null;
		if (generator != null) generator.stop();
		if (world != null && worker != null) worker.close(world);
		world = null;
		worldLevel = null;
	}

	// ---- Chunks ----

	private static void chunkUnloaded(ClientLevel level, LevelChunk chunk) {
		if (!recording()) return;
		try {
			if (renderer != null && level == worldLevel) renderer.chunkGone(chunk.getPos().x(), chunk.getPos().z());
			LodWorker wk = worker();
			if (wk.hasRoom() && lit(level, chunk)) wk.add(new LodSnapshot(world(level), level, chunk));
		} catch (RuntimeException e) {
			fail("copying a chunk", e);
		}
	}

	/**
	 * Whether the chunk's light has come in. A chunk can leave before it does (flying along the edge of the render
	 * distance), and without light every cave looks open to the sky: copied like that, its cave walls showed as grey
	 * stone at the far terrain's edge.
	 */
	static boolean lit(ClientLevel level, LevelChunk chunk) {
		return level.getLightEngine().lightOnInColumn(SectionPos.getZeroNode(chunk.getPos().x(), chunk.getPos().z()));
	}

	/** A section of the game's got a mesh or lost it; any thread. */
	public static void sectionChanged(long sectionNode) {
		LodRenderer r = renderer;
		if (r != null) r.sectionChanged(SectionPos.x(sectionNode), SectionPos.z(sectionNode));
	}

	/** The level is going away (another dimension, or leaving the world): keeps the chunks still loaded around the player. */
	public static void leave(ClientLevel level) {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (recording() && mc.player != null) {
				LodWorld w = world(level);
				LodWorker wk = worker();
				ChunkPos center = mc.player.chunkPosition();
				int r = mc.options.getEffectiveRenderDistance() + 3;
				for (int z = center.z() - r; z <= center.z() + r && wk.hasRoom(); z++) {
					for (int x = center.x() - r; x <= center.x() + r && wk.hasRoom(); x++) {
						LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
						if (chunk != null && lit(level, chunk)) wk.add(new LodSnapshot(w, level, chunk));
					}
				}
			}
		} catch (RuntimeException e) {
			fail("keeping the loaded chunks", e);
		}
		closeWorld();
	}

	private static void stop() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) leave(mc.level);
		closeWorld();
		if (worker != null) worker.stop(5000);
	}

	// ---- Drawing ----

	/** Before the main pass is recorded. */
	public static void prepare(LevelRenderer levelRenderer, CameraRenderState camera, ChunkSectionsToRender sections) {
		prepared = false;
		waterPending = false;
		terrainUniform = null;
		if (failed && renderer != null) closeQuietly();
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || !active() || fogHides) return;
		try {
			LodColors.refresh(mc);
			LodWorld w = world(level);
			// Turned on: every node is built again, the land it's missing made.
			if (LodSettings.unseenLand() && !unseenLand) w.epoch++;
			unseenLand = LodSettings.unseenLand();
			if (renderer == null) renderer = new LodRenderer(w, worker());
			throughPack = Shaderpacks.active();
			terrainUniform = ((ChunkSectionsAccessor) sections).afterburner$terrainTransformUBO();
			renderer.prepare(mc, levelRenderer, level, camera, throughPack ? Shaderpacks.farTerrainProjection() : null);
			prepared = true;
		} catch (RuntimeException e) {
			fail("preparing", e);
		}
	}

	/** In the main pass, right after the solid terrain. */
	public static void draw(RenderPass pass, ChunkSectionsToRender sections) {
		if (!prepared || renderer == null || throughPack) return;
		prepared = false;
		try {
			renderer.draw(pass, ((ChunkSectionsAccessor) sections).afterburner$terrainTransformUBO());
		} catch (RuntimeException e) {
			// The pass still uses the buffers: they're let go before the next frame (prepare).
			failed = true;
			LOGGER.error("[Afterburner] Far terrain failed while drawing; it's off until restart", e);
		}
	}

	/** The game's pipeline for the far terrain: what a shader pack's dh_terrain is made for. */
	public static RenderPipeline packPipeline() {
		return LodRenderer.pipeline();
	}

	/** The game's pipeline for the far terrain's water: what a shader pack's dh_water is made for. */
	public static RenderPipeline packWaterPipeline() {
		return LodRenderer.waterPipeline();
	}

	/** Afterburner's own pipeline for the floor under the far water, in a shader pack's depth (see LodRenderer). */
	public static RenderPipeline packFloorPipeline() {
		return LodRenderer.floorPipeline();
	}

	/** Whether there's far terrain for the shader pack to draw this frame. */
	public static boolean drawsForPack() {
		LodRenderer r = renderer;
		return prepared && throughPack && r != null && terrainUniform != null && r.hasDraws();
	}

	/** Whether some of this frame's far terrain is water. Before {@link #drawForPack}. */
	public static boolean hasWaterForPack() {
		LodRenderer r = renderer;
		return drawsForPack() && r.hasWater();
	}

	/**
	 * The shader pack's pass for it: drawn with its pipeline ({@code pipeline}, made from {@link #packPipeline}).
	 * {@code withoutWater}: the water is left for {@link #drawWaterForPack}.
	 */
	public static void drawForPack(RenderPass pass, CompiledRenderPipeline pipeline, boolean withoutWater) {
		LodRenderer r = renderer;
		GpuBufferSlice terrain = terrainUniform;
		if (!prepared || !throughPack || r == null || terrain == null) return;
		prepared = false;
		waterPending = withoutWater;
		try {
			r.drawWith(pass, pipeline, terrain, withoutWater ? LodRenderer.SOLID : LodRenderer.ALL);
		} catch (RuntimeException e) {
			failed = true;
			waterPending = false;
			LOGGER.error("[Afterburner] Far terrain failed while drawing; it's off until restart", e);
		}
	}

	/** Whether {@link #drawForPack} left the water to draw. */
	public static boolean waterPendingForPack() {
		return waterPending && !failed && renderer != null && terrainUniform != null;
	}

	/**
	 * The water {@link #drawForPack} left, with {@code pipeline}: the floor's ({@link #packFloorPipeline}) or the pack's for
	 * it (made from {@link #packWaterPipeline}, the last).
	 */
	public static void drawWaterForPack(RenderPass pass, CompiledRenderPipeline pipeline, boolean last) {
		LodRenderer r = renderer;
		GpuBufferSlice terrain = terrainUniform;
		if (!waterPendingForPack() || r == null || terrain == null) return;
		if (last) waterPending = false;
		try {
			r.drawWith(pass, pipeline, terrain, LodRenderer.WATER);
		} catch (RuntimeException e) {
			failed = true;
			waterPending = false;
			LOGGER.error("[Afterburner] Far terrain failed while drawing; it's off until restart", e);
		}
	}

	/** The camera's far plane: past the far terrain's edge. Not with a shader pack: it draws it with its own projection. */
	public static float depthFar(float far) {
		return active() && !fogHides && !Shaderpacks.active() ? Math.max(far, blocks() * 1.5F) : far;
	}

	/** Moves the render distance fog to the far terrain's edge, and the haze of distance with it. */
	public static void adjustFog(FogData fog, Camera camera, int renderDistanceChunks) {
		fogHides = false;
		if (!active()) return;
		float lod = blocks(), vanilla = renderDistanceChunks * 16.0F;
		if (lod <= vanilla || camera.getFluidInCamera() != FogType.NONE || fog.environmentalEnd <= vanilla) {
			fogHides = true;
			return;
		}
		// Shader packs do their own fog (with dhRenderDistance).
		if (Shaderpacks.active()) return;
		float span = Mth.clamp(lod / 10.0F, 4.0F, 64.0F);
		fog.renderDistanceStart = lod - span;
		fog.renderDistanceEnd = lod;
		// The sky's own haze ends at 1024 blocks; further terrain would vanish in it.
		if (fog.environmentalEnd >= 256.0F && lod > 1024.0F) {
			float scale = lod / 1024.0F;
			fog.environmentalStart *= scale;
			fog.environmentalEnd *= scale;
		}
	}

	private static void fail(String what, RuntimeException e) {
		if (failed) return;
		failed = true;
		LOGGER.error("[Afterburner] Far terrain failed while {}; it's off until restart", what, e);
		closeQuietly();
	}

	private static void closeQuietly() {
		try {
			closeWorld();
		} catch (RuntimeException ignored) {
			// Already failing.
		}
	}
}
