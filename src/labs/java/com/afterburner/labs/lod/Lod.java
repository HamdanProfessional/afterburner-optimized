package com.afterburner.labs.lod;

import com.afterburner.labs.LabsFeatures;
import com.afterburner.labs.shaderpack.game.Shaderpacks;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.level.storage.LevelResource;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Far terrain: the land past the render distance, drawn as simple colored blocks from the chunks seen before. Every
 * chunk the game lets go of is copied ({@link LodSnapshot}) and turned into coarser and coarser columns on a background
 * thread ({@link LodWorker}), kept on disk per world and dimension ({@link LodWorld}), and drawn after the solid and
 * cutout terrain ({@link LodRenderer}). The render distance fog moves out to the far terrain's edge.
 * <p>
 * With a shader pack that has Distant Horizons' programs, the pack draws it with its dh_terrain, as it would Distant Horizons'
 * (see PackRenderer#drawFarTerrain), and does its own fog: see {@link #drawForPack}.
 * <p>
 * Our own design: it borrows the idea (keep what was seen, simplify it level by level) from Voxy and Distant Horizons,
 * none of their code, and needs no other mod.
 */
public final class Lod {
	/**
	 * The far terrain's vertex attributes, by location: 0 {@code uint Position} (x, z and the face in bits 0-16 as
	 * (z * 129 + x) * 6 + face, faces down, up, north, south, west, east; the material in bits 17-19; y in 20-31; all in voxels
	 * from the node's corner), read with glVertexAttribIPointer; 1 {@code vec4 Color} (four unsigned bytes, normalized: the
	 * color, and in alpha the sky light in the high 4 bits and the block light in the low 4). A program drawing it binds these
	 * names to these locations before it's linked. 8 bytes a vertex, 4 vertices a quad.
	 */
	public static final String[] PACK_ATTRIBUTES = {"Position", "Color"};
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
		ResourceLocation dimension = level.dimension().location();
		Path folder = mc.gameDirectory.toPath().resolve("afterburner").resolve("lod").resolve(worldName(mc))
				.resolve(clean(dimension.getNamespace() + "_" + dimension.getPath()));
		world = new LodWorld(folder, level.getMinBuildHeight(), level.getHeight(), level.dimensionType().hasSkyLight(),
				level.registryAccess().registryOrThrow(Registries.BIOME));
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
			if (renderer != null && level == worldLevel) renderer.chunkGone(chunk.getPos().x, chunk.getPos().z);
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
		return level.getLightEngine().lightOnInSection(SectionPos.of(chunk.getPos(), 0));
	}

	/** A section of the game's in this chunk got a mesh or lost it; any thread. */
	public static void sectionChanged(int chunkX, int chunkZ) {
		LodRenderer r = renderer;
		if (r != null) r.sectionChanged(chunkX, chunkZ);
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
				for (int z = center.z - r; z <= center.z + r && wk.hasRoom(); z++) {
					for (int x = center.x - r; x <= center.x + r && wk.hasRoom(); x++) {
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

	/**
	 * In LevelRenderer.renderLevel, before the solid terrain: {@code modelView} and {@code projection} are the ones it's drawn
	 * with (the camera's rotation, and the projection with the far plane {@link #depthFar} moved out).
	 */
	public static void prepare(LevelRenderer levelRenderer, Camera camera, Matrix4f modelView, Matrix4f projection) {
		prepared = false;
		waterPending = false;
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
			renderer.prepare(mc, levelRenderer, level, camera, modelView, projection, throughPack ? Shaderpacks.farTerrainProjection() : null);
			prepared = true;
		} catch (RuntimeException e) {
			fail("preparing", e);
		}
		// With a pack, it draws the far terrain now, before the game's terrain.
		if (prepared && throughPack) Shaderpacks.farTerrain();
	}

	/** In LevelRenderer.renderLevel, right after the solid and cutout terrain. */
	public static void draw() {
		if (!prepared || renderer == null || throughPack) return;
		prepared = false;
		try {
			renderer.draw();
		} catch (RuntimeException e) {
			failed = true;
			LOGGER.error("[Afterburner] Far terrain failed while drawing; it's off until restart", e);
		}
	}

	/*
	 * Drawn by a shader pack: between prepare (before the solid terrain) and the end of LevelRenderer.renderLevel, on the
	 * render thread, the pack calls drawForPack once (with its dh_terrain), then while waterPendingForPack drawWaterForPack,
	 * with floorProgram() (last false) and with its dh_water (last true).
	 *
	 * Each draw is with a linked GL program already in use (glUseProgram is the caller's; Lod never calls it on a pack's
	 * program), with PACK_ATTRIBUTES bound to locations 0 and 1, into the framebuffer the caller bound, with the depth test,
	 * depth writes, blending and culling as the caller set them. The floor program writes depth only (gl_FragDepth, no color
	 * output): the caller has color writes off for it, or a framebuffer without color.
	 *
	 * Lod sets these uniforms, found by name with glGetUniformLocation (once a frame per program id), skipping any the
	 * program doesn't have (-1):
	 *   ivec3 CameraBlockPos  the camera's block (the floor of its position);
	 *   vec3  CameraOffset    CameraBlockPos minus the camera's exact position (each in (-1, 0]);
	 *   mat4  ModelViewMat    the camera's rotation: the view matrix renderLevel draws the terrain with;
	 *   mat4  DhProjection    Shaderpacks.farTerrainProjection() as of prepare (OpenGL depth);
	 *   ivec4 DrawnY          x to y: the heights (blocks) the game draws its chunks between (on 1.21.1, all of the world);
	 * and per node, before its draw:
	 *   ivec4 NodeOrigin      xyz: the node's corner in world blocks (y: the world's bottom), w: blocks per voxel;
	 *   ivec4 NodeFlags       x: 1 for nodes at levels 0 and 1 (whose quads never cross a chunk's border), else 0.
	 * A vertex is at vec3(NodeOrigin.xyz - CameraBlockPos) + voxel * NodeOrigin.w + CameraOffset from the camera, voxel being
	 * vec3((Position & 131071u) / 6u % 129u, Position >> 20u, (Position & 131071u) / 774u). Everything else (ProjMat, the
	 * fog, the lightmap and other textures, the pack's own uniforms) is the caller's. The quads are drawn as triangles with
	 * the game's quad index buffer (RenderSystem.getSequentialBuffer(QUADS)), so gl_VertexID & 3 is the corner in its quad.
	 *
	 * Left behind: the program still in use, with these uniforms at the last node's values; vertex array 0 bound and
	 * BufferUploader invalidated; textures, framebuffer, depth, blend and cull state as they were.
	 */

	/** Whether there's far terrain for the shader pack to draw this frame. */
	public static boolean drawsForPack() {
		LodRenderer r = renderer;
		return prepared && throughPack && r != null && r.hasDraws();
	}

	/** Whether some of this frame's far terrain is water. Before {@link #drawForPack}. */
	public static boolean hasWaterForPack() {
		LodRenderer r = renderer;
		return drawsForPack() && r.hasWater();
	}

	/**
	 * The shader pack's pass for it, with its dh_terrain {@code program} in use (see the contract above). {@code withoutWater}:
	 * the water is left for {@link #drawWaterForPack}.
	 */
	public static void drawForPack(int program, boolean withoutWater) {
		LodRenderer r = renderer;
		if (!prepared || !throughPack || r == null) return;
		prepared = false;
		waterPending = withoutWater;
		try {
			r.drawWith(program, withoutWater ? LodRenderer.SOLID : LodRenderer.ALL);
		} catch (RuntimeException e) {
			failed = true;
			waterPending = false;
			LOGGER.error("[Afterburner] Far terrain failed while drawing; it's off until restart", e);
		}
	}

	/** Whether {@link #drawForPack} left the water to draw. */
	public static boolean waterPendingForPack() {
		return waterPending && !failed && renderer != null;
	}

	/**
	 * Afterburner's own program for the floor under the far water, in the pack's depth (far_water_floor, see
	 * LodRenderer#floorProgram), built the first time it's asked for: the caller puts it in use for the first
	 * {@link #drawWaterForPack}. 0 if it didn't build (that draw is then left out).
	 */
	public static int floorProgram() {
		return LodRenderer.floorProgram();
	}

	/**
	 * The water {@link #drawForPack} left, with {@code program} in use: {@link #floorProgram} ({@code last} false), then the
	 * pack's dh_water ({@code last} true, the last).
	 */
	public static void drawWaterForPack(int program, boolean last) {
		LodRenderer r = renderer;
		if (!waterPendingForPack() || r == null) return;
		if (last) waterPending = false;
		if (program == 0) return;
		try {
			r.drawWith(program, LodRenderer.WATER);
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

	/**
	 * After the terrain's fog is set up ({@code renderDistance}: the game's, in blocks): moves the render distance fog to the
	 * far terrain's edge, as the game's own is at the edge of its chunks.
	 */
	public static void adjustFog(Camera camera, FogRenderer.FogMode mode, float renderDistance, boolean thickFog) {
		if (mode != FogRenderer.FogMode.FOG_TERRAIN) return;
		fogHides = false;
		if (!active()) return;
		float lod = blocks();
		// Under water or lava, in the Nether's (or a boss's) thick fog, blind or in the dark: the fog ends nearer than the
		// game's own chunks do.
		if (lod <= renderDistance || camera.getFluidInCamera() != FogType.NONE || thickFog || RenderSystem.getShaderFogEnd() < renderDistance) {
			fogHides = true;
			return;
		}
		// Shader packs do their own fog (with dhRenderDistance).
		if (Shaderpacks.active()) return;
		float span = Mth.clamp(lod / 10.0F, 4.0F, 64.0F);
		RenderSystem.setShaderFogStart(lod - span);
		RenderSystem.setShaderFogEnd(lod);
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
