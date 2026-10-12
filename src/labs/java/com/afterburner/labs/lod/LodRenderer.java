package com.afterburner.labs.lod;

import com.afterburner.Mobile;
import com.afterburner.client.perf.FpsTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;

import static org.lwjgl.opengl.GL32C.*;

/**
 * Draws one world's far terrain: picks the nodes to show (finer near the player, coarser further out, never coarse
 * ones over the chunks the game draws itself), asks the worker for their meshes, uploads what it built, and draws them
 * right after the solid and cutout terrain, with the solid terrain's render state. Render thread only.
 * <p>
 * Where the game's own chunks are, the vertex shader hides the far terrain chunk by chunk: a mask of 256 x 256 chunks
 * (wrapping around) says which ones the game draws, and quads at levels 0 and 1 never cross a chunk's border.
 * <p>
 * Drawn by a shader pack instead (its dh_terrain, see Lod), it has the pack's projection for it, and no mask: the game's
 * chunks are drawn over it, and the pack hides it near the player itself; under the game's chunks near their edge it's kept,
 * for packs that fade those out there. A pack with dh_water draws the water on its own, see-through, after the rest.
 * <p>
 * Plain OpenGL: a vertex array per node over its vertex buffer and the game's shared quad index buffer, and programs of
 * its own (far_terrain, far_water_floor) read from the resources. It puts back the program, the vertex array and the
 * active texture unit it finds.
 */
final class LodRenderer {
	private static final Logger LOGGER = LoggerFactory.getLogger("afterburner");
	/** Position (one unsigned int, see LodMesher) and Color (four bytes): {@link Lod#PACK_ATTRIBUTES}, at locations 0 and 1. */
	private static final int VERTEX_BYTES = 8;
	/** A node is split into finer ones while the player is nearer than this many of its voxels. */
	private static final int DETAIL = 256;
	/** Bytes uploaded per frame; less on phones, where the GL layer copies each upload once more. */
	private static final long UPLOAD_BUDGET = (Mobile.ACTIVE ? 1L : 4L) << 20;
	/** A built node whose chunks changed is rebuilt at most this often (nanoseconds, doubling per level up to 16 s). */
	private static final long REBUILD_AFTER = 2_000_000_000L;
	/** Added to a rebuild's priority, so places with nothing drawn yet come first. */
	private static final double STALE_PENALTY = 1e6;
	private static final int KEEP_FRAMES = 600, MASK = 256;
	/** Every chunk is asked again one row in this many per frame, in case a change got past {@link #sectionChanged}. */
	private static final int MASK_ROWS = 64;
	/** Loaded chunks copied for the far terrain per frame at most ({@link #copyLoaded}). */
	private static final int COPIES_PER_FRAME = 4;
	/** How long a chunk whose sections changed is asked every frame (ms). */
	private static final long WATCH_FOR = 1000;
	/**
	 * With a shader pack, nodes the game's chunks cover are kept from this far out (of the render distance): Complementary
	 * hides the far terrain nearer than 0.4 of it, MakeUp fades the game's chunks out from 0.9.
	 */
	private static final double PACK_UNDER = 0.35;
	/** Texture units: the lightmap where the game has it, the mask past the units the game's state tracking covers (0-11). */
	private static final int LIGHTMAP_UNIT = 2, MASK_UNIT = 13;
	/** The far terrain's own program and the floor's for shader packs; 0 until made. */
	private static int program, floorProgram;
	private static int projLocation, fogStartLocation, fogEndLocation, fogColorLocation, fogShapeLocation;
	private static boolean failed, floorFailed;
	/** What of a node {@link #drawNodes} draws: all of it, all but the water, or the water. */
	static final int ALL = 0, SOLID = 1, WATER = 2;

	private static final class Node {
		final int level, nx, nz;
		int version = -1, quads, waterQuads, usedFrame;
		/** When the drawn mesh was uploaded (System.nanoTime). */
		long builtAt;
		/** In blocks, for the frustum test. */
		int minY, maxY;
		double distance;
		/** The vertex buffer and the vertex array over it; 0 for none. */
		int buffer, array;

		Node(int level, int nx, int nz) {
			this.level = level;
			this.nx = nx;
			this.nz = nz;
		}

		void close() {
			if (array != 0) GlStateManager._glDeleteVertexArrays(array);
			if (buffer != 0) GlStateManager._glDeleteBuffers(buffer);
			buffer = array = 0;
		}
	}

	/** Where a program has the uniforms set here; -1 for one it doesn't have. */
	private record Uniforms(int nodeOrigin, int nodeFlags, int cameraBlockPos, int cameraOffset, int modelView, int dhProjection, int drawnY) {
		static Uniforms of(int program) {
			return new Uniforms(glGetUniformLocation(program, "NodeOrigin"), glGetUniformLocation(program, "NodeFlags"),
					glGetUniformLocation(program, "CameraBlockPos"), glGetUniformLocation(program, "CameraOffset"),
					glGetUniformLocation(program, "ModelViewMat"), glGetUniformLocation(program, "DhProjection"),
					glGetUniformLocation(program, "DrawnY"));
		}
	}

	final LodWorld world;
	private final LodWorker worker;
	private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
	private final ArrayList<Node> draws = new ArrayList<>();
	private final byte[] mask = new byte[MASK * MASK];
	/**
	 * Per chunk, like the mask: whether the game has a mesh there, and the sections (from the world's bottom one) its land
	 * ends below and its lowest ground is in. The mask is made from these ({@link #maskValue}).
	 */
	private final byte[] compiled = new byte[MASK * MASK], top = new byte[MASK * MASK], ground = new byte[MASK * MASK];
	private final ByteBuffer maskData = MemoryUtil.memAlloc(MASK * MASK);
	private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
	/** The mask's buffer and the buffer texture (R8UI) over it; 0 until made. */
	private int maskBuffer, maskTexture;
	private boolean maskChanged = true, closed;
	/** The game's draw distance in chunks, the camera's chunk and FpsTarget's cap, as the mask was made for. */
	private int viewDistance = -1, viewX, viewZ, viewCap;
	/** The sections the game draws, from the world's bottom one (first, and one past the last): all of them on 1.21.1. */
	private int bandLo, bandHi;
	/** Loaded chunks copied for the far terrain (the game's own copy comes when a chunk unloads), and those to copy. */
	private final LongOpenHashSet copied = new LongOpenHashSet();
	private final LongLinkedOpenHashSet toCopy = new LongLinkedOpenHashSet();
	private int frame, lodBlocks;
	/** System.nanoTime at the start of this frame's prepare. */
	private long now;
	/** The game's chunks (plus a margin), in chunks: coarse nodes stay out of it. */
	private int vMinX, vMaxX, vMinZ, vMaxZ;
	private boolean hasSquare;
	/** Chunks (x << 32 | z) whose sections got or lost a mesh, from any thread (guarded by itself). */
	private final LongArrayList changedChunks = new LongArrayList();
	/** Chunks asked every frame, until the game draws them or the time (Util.getMillis) is up. */
	private final Long2LongOpenHashMap watched = new Long2LongOpenHashMap();
	private double camX, camZ;
	/** This frame's camera: its block, and that block's corner minus where it is exactly (a negative fraction). */
	private int camBlockX, camBlockY, camBlockZ;
	private float camOffsetX, camOffsetY, camOffsetZ;
	private final Matrix4f modelView = new Matrix4f(), projection = new Matrix4f(), packProjection = new Matrix4f();
	private @Nullable Frustum frustum;
	/** Drawn by a shader pack this frame. */
	private boolean forPack;
	/** This frame's programs' uniforms, by program (asked once a frame: a pack's programs can be made again). */
	private final Int2ObjectOpenHashMap<Uniforms> uniforms = new Int2ObjectOpenHashMap<>();

	LodRenderer(LodWorld world, LodWorker worker) {
		this.world = world;
		this.worker = worker;
	}

	/** The far terrain's own program (far_terrain.vsh/fsh); 0 if it didn't build. */
	private static int program() {
		if (program == 0 && !failed) {
			try {
				program = build("far_terrain");
				projLocation = glGetUniformLocation(program, "ProjMat");
				fogStartLocation = glGetUniformLocation(program, "FogStart");
				fogEndLocation = glGetUniformLocation(program, "FogEnd");
				fogColorLocation = glGetUniformLocation(program, "FogColor");
				fogShapeLocation = glGetUniformLocation(program, "FogShape");
				int previous = GlStateManager._getInteger(GL_CURRENT_PROGRAM);
				GlStateManager._glUseProgram(program);
				glUniform1i(glGetUniformLocation(program, "Sampler2"), LIGHTMAP_UNIT);
				glUniform1i(glGetUniformLocation(program, "LodMask"), MASK_UNIT);
				GlStateManager._glUseProgram(previous);
			} catch (IOException | RuntimeException e) {
				failed = true;
				program = 0;
				LOGGER.error("[Afterburner] The far terrain shader didn't load; far terrain is off until restart", e);
			}
		}
		return program;
	}

	/**
	 * Under a shader pack's water, the depth of a floor (far_water_floor.fsh): the far terrain keeps no ground under its
	 * water, and packs see through water to what's under it. Depth only (it writes no color), with the pack's depth (nearer
	 * is less). 0 if it didn't build.
	 */
	static int floorProgram() {
		if (floorProgram == 0 && !floorFailed) {
			try {
				floorProgram = build("far_water_floor");
			} catch (IOException | RuntimeException e) {
				floorFailed = true;
				floorProgram = 0;
				LOGGER.error("[Afterburner] The far terrain's water floor shader didn't load", e);
			}
		}
		return floorProgram;
	}

	/** Links {@code name}.vsh and .fsh (assets/afterburner/shaders/core), with the vertex attributes at their locations. */
	private static int build(String name) throws IOException {
		int vertex = 0, fragment = 0, linked = glCreateProgram();
		try {
			vertex = shader(GL_VERTEX_SHADER, name + ".vsh");
			fragment = shader(GL_FRAGMENT_SHADER, name + ".fsh");
			glAttachShader(linked, vertex);
			glAttachShader(linked, fragment);
			for (int i = 0; i < Lod.PACK_ATTRIBUTES.length; i++) glBindAttribLocation(linked, i, Lod.PACK_ATTRIBUTES[i]);
			glLinkProgram(linked);
			if (glGetProgrami(linked, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(name + " didn't link: " + glGetProgramInfoLog(linked));
			return linked;
		} catch (IOException | RuntimeException e) {
			glDeleteProgram(linked);
			throw e;
		} finally {
			// Freed with the program.
			if (vertex != 0) glDeleteShader(vertex);
			if (fragment != 0) glDeleteShader(fragment);
		}
	}

	private static int shader(int type, String file) throws IOException {
		String source;
		try (InputStream in = Minecraft.getInstance().getResourceManager().open(ResourceLocation.fromNamespaceAndPath("afterburner", "shaders/core/" + file))) {
			source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		int shader = glCreateShader(type);
		glShaderSource(shader, source);
		glCompileShader(shader);
		if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
			String log = glGetShaderInfoLog(shader);
			glDeleteShader(shader);
			throw new IllegalStateException(file + " didn't compile: " + log);
		}
		return shader;
	}

	/**
	 * Before the solid terrain: uploads, picks the nodes, updates the mask. {@code modelView} and {@code projection} are
	 * the ones the terrain is drawn with; {@code packProjection} is the shader pack's for it, when one draws it.
	 */
	void prepare(Minecraft mc, LevelRenderer levelRenderer, ClientLevel level, Camera camera, Matrix4fc modelView, Matrix4fc projection,
			@Nullable Matrix4fc packProjection) {
		frame++;
		now = System.nanoTime();
		draws.clear();
		uniforms.clear();
		if (failed || closed) return;
		receive();
		Vec3 pos = camera.getPosition();
		camX = pos.x;
		camZ = pos.z;
		camBlockX = Mth.floor(pos.x);
		camBlockY = Mth.floor(pos.y);
		camBlockZ = Mth.floor(pos.z);
		camOffsetX = (float) (camBlockX - pos.x);
		camOffsetY = (float) (camBlockY - pos.y);
		camOffsetZ = (float) (camBlockZ - pos.z);
		this.modelView.set(modelView);
		this.projection.set(projection);
		forPack = packProjection != null;
		if (packProjection != null) this.packProjection.set(packProjection);
		// The game's projection reaches past the far terrain (Lod#depthFar); a pack's is its own.
		frustum = new Frustum(new Matrix4f(modelView), new Matrix4f(forPack ? this.packProjection : this.projection));
		frustum.prepare(pos.x, pos.y, pos.z);
		lodBlocks = LodSettings.drawn() * 16;
		int rd = mc.options.getEffectiveRenderDistance();
		int cx = Mth.floor(pos.x) >> 4, cz = Mth.floor(pos.z) >> 4;
		updateMask(levelRenderer, level, cx, cz, rd);
		copyLoaded(level);

		int root = LodMesher.NODE << LodWorld.MAX_LEVEL;
		int minX = Math.floorDiv(Mth.floor(pos.x) - lodBlocks, root), maxX = Math.floorDiv(Mth.floor(pos.x) + lodBlocks, root);
		int minZ = Math.floorDiv(Mth.floor(pos.z) - lodBlocks, root), maxZ = Math.floorDiv(Mth.floor(pos.z) + lodBlocks, root);
		for (int nz = minZ; nz <= maxZ; nz++) {
			for (int nx = minX; nx <= maxX; nx++) visit(LodWorld.MAX_LEVEL, nx, nz);
		}
		draws.sort(Comparator.comparingDouble(n -> n.distance));
		// Requests nobody wants any more (the player moved on), unless the worker is on them.
		int thisFrame = frame;
		worker.requests.values().removeIf(r -> r.world == world && r.frame != thisFrame && !r.taken.get());
		if (frame % 60 == 0) evict();

		if (maskBuffer == 0) {
			maskBuffer = GlStateManager._glGenBuffers();
			GlStateManager._glBindBuffer(GL_TEXTURE_BUFFER, maskBuffer);
			glBufferData(GL_TEXTURE_BUFFER, MASK * MASK, GL_DYNAMIC_DRAW);
			maskTexture = glGenTextures();
			int active = GlStateManager._getActiveTexture();
			glActiveTexture(GL_TEXTURE0 + MASK_UNIT);
			glBindTexture(GL_TEXTURE_BUFFER, maskTexture);
			glTexBuffer(GL_TEXTURE_BUFFER, GL_R8UI, maskBuffer);
			glActiveTexture(active);
			maskChanged = true;
		}
		if (maskChanged) {
			maskData.clear();
			maskData.put(mask).flip();
			GlStateManager._glBindBuffer(GL_TEXTURE_BUFFER, maskBuffer);
			glBufferSubData(GL_TEXTURE_BUFFER, 0, maskData);
			maskChanged = false;
		}
		GlStateManager._glBindBuffer(GL_TEXTURE_BUFFER, 0);
		// With a pack it's drawn with the pack's program for it.
		if (!forPack && !draws.isEmpty() && program() == 0) draws.clear();
	}

	/** Whether there's anything to draw this frame. */
	boolean hasDraws() {
		return !draws.isEmpty() && maskTexture != 0;
	}

	/** Whether any of it is water this frame. */
	boolean hasWater() {
		if (!hasDraws()) return false;
		for (Node n : draws) {
			if (n.waterQuads > 0) return true;
		}
		return false;
	}

	/** After the solid and cutout terrain, with the game's projection, fog and lightmap, hidden under its chunks by the mask. */
	void draw() {
		if (!hasDraws() || forPack) return;
		int own = program();
		if (own == 0) return;
		// Depth test and writes, culling, no blending, the lightmap as the game's shader texture 2.
		RenderType.solid().setupRenderState();
		int previous = GlStateManager._getInteger(GL_CURRENT_PROGRAM);
		try {
			GlStateManager._glUseProgram(own);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				glUniformMatrix4fv(projLocation, false, projection.get(stack.mallocFloat(16)));
				float[] fogColor = RenderSystem.getShaderFogColor();
				glUniform4f(fogColorLocation, fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
			}
			glUniform1f(fogStartLocation, RenderSystem.getShaderFogStart());
			glUniform1f(fogEndLocation, RenderSystem.getShaderFogEnd());
			glUniform1i(fogShapeLocation, RenderSystem.getShaderFogShape().getIndex());
			int active = GlStateManager._getActiveTexture();
			// Past the units the game tracks: set straight, then the active unit put back as it has it.
			glActiveTexture(GL_TEXTURE0 + MASK_UNIT);
			glBindTexture(GL_TEXTURE_BUFFER, maskTexture);
			glActiveTexture(active);
			GlStateManager._activeTexture(GL_TEXTURE0 + LIGHTMAP_UNIT);
			GlStateManager._bindTexture(RenderSystem.getShaderTexture(2));
			GlStateManager._activeTexture(active);
			drawNodes(uniforms(own), ALL);
		} finally {
			GlStateManager._glUseProgram(previous);
			RenderType.solid().clearRenderState();
		}
	}

	/**
	 * With a program that's in use (a shader pack's for it, or {@link #floorProgram}): its other uniforms and textures are
	 * the caller's. {@code part}: {@link #ALL}, {@link #SOLID} or {@link #WATER}.
	 */
	void drawWith(int program, int part) {
		if (!hasDraws()) return;
		drawNodes(uniforms(program), part);
	}

	private Uniforms uniforms(int program) {
		Uniforms u = uniforms.get(program);
		if (u == null) uniforms.put(program, u = Uniforms.of(program));
		return u;
	}

	/** Sets the program's view uniforms, then draws {@code part} of each node; leaves no vertex array bound. */
	private void drawNodes(Uniforms u, int part) {
		if (u.cameraBlockPos() >= 0) glUniform3i(u.cameraBlockPos(), camBlockX, camBlockY, camBlockZ);
		if (u.cameraOffset() >= 0) glUniform3f(u.cameraOffset(), camOffsetX, camOffsetY, camOffsetZ);
		if (u.drawnY() >= 0) glUniform4i(u.drawnY(), world.minY + bandLo * 16, world.minY + bandHi * 16, 0, 0);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			if (u.modelView() >= 0) glUniformMatrix4fv(u.modelView(), false, modelView.get(stack.mallocFloat(16)));
			if (u.dhProjection() >= 0 && forPack) glUniformMatrix4fv(u.dhProjection(), false, packProjection.get(stack.mallocFloat(16)));
		}
		RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
		BufferUploader.invalidate();
		try {
			for (Node n : draws) {
				if (n.array == 0) continue;
				// The water's quads are last.
				int solid = n.quads - n.waterQuads;
				int count = part == ALL ? n.quads : part == SOLID ? solid : n.waterQuads;
				if (count == 0) continue;
				GlStateManager._glBindVertexArray(n.array);
				// Into this vertex array, as long as the node needs it.
				indices.bind(n.quads * 6);
				VertexFormat.IndexType type = indices.type();
				int size = LodMesher.NODE << n.level;
				if (u.nodeOrigin() >= 0) glUniform4i(u.nodeOrigin(), n.nx * size, world.minY, n.nz * size, 1 << n.level);
				if (u.nodeFlags() >= 0) glUniform4i(u.nodeFlags(), n.level <= 1 ? 1 : 0, 0, 0, 0);
				glDrawElements(GL_TRIANGLES, count * 6, type.asGLType, part == WATER ? (long) solid * 6 * type.bytes : 0L);
			}
		} finally {
			GlStateManager._glBindVertexArray(0);
			BufferUploader.invalidate();
		}
	}

	// ---- Picking nodes ----

	private void visit(int level, int nx, int nz) {
		int size = LodMesher.NODE << level;
		int x0 = nx * size, z0 = nz * size;
		double distance = distance(x0, z0, size);
		if (distance > lodBlocks) return;
		long key = LodWorld.key(level, nx, nz);
		Node node = nodes.get(key);
		boolean built = node != null && node.version >= 0;
		double minY = world.minY, maxY = world.minY + world.height;
		if (built && node.quads > 0) {
			minY = node.minY;
			maxY = node.maxY;
		}
		if (!frustum.isVisible(new AABB(x0, minY, z0, x0 + size, maxY, z0 + size))) {
			if (node != null) node.usedFrame = frame;
			return;
		}
		boolean coarse = level >= 2 && overlapsSquare(x0, z0, size);
		boolean split = level > 0 && (distance < (double) (DETAIL << level) || coarse);
		if (!split) {
			use(level, nx, nz, x0, z0, size, distance);
			return;
		}
		// An empty node that's up to date has nothing below it either.
		if (built && node.quads == 0 && node.version == world.version(level, nx, nz)) {
			node.usedFrame = frame;
			return;
		}
		int half = size >> 1;
		boolean ready = true;
		for (int i = 0; i < 4; i++) {
			int cx = nx * 2 + (i & 1), cz = nz * 2 + (i >> 1);
			if (distance(cx * half, cz * half, half) > lodBlocks) continue;
			Node child = nodes.get(LodWorld.key(level - 1, cx, cz));
			if (child == null || child.version < 0) {
				ready = false;
				break;
			}
		}
		if (ready || !built || coarse) {
			for (int i = 0; i < 4; i++) visit(level - 1, nx * 2 + (i & 1), nz * 2 + (i >> 1));
			return;
		}
		// Until the finer nodes are built, this one stands in for them.
		use(level, nx, nz, x0, z0, size, distance);
		for (int i = 0; i < 4; i++) {
			int cx = nx * 2 + (i & 1), cz = nz * 2 + (i >> 1);
			double d = distance(cx * half, cz * half, half);
			if (d <= lodBlocks) request(level - 1, cx, cz, d);
		}
	}

	private void use(int level, int nx, int nz, int x0, int z0, int size, double distance) {
		long key = LodWorld.key(level, nx, nz);
		Node node = nodes.get(key);
		// Nodes the game's own chunks cover completely (with a pack, those near the player: see PACK_UNDER).
		// Those near the circle's edge are made anyway (the mask hides them), ready for when the game lets go of them.
		if (level <= 1 && covered(x0, z0, size) && deepInside(x0, z0, size)
				&& (!forPack || farthest(x0, z0, size) < PACK_UNDER * viewDistance * 16)) {
			if (node != null) node.usedFrame = frame;
			return;
		}
		if (node == null) {
			node = new Node(level, nx, nz);
			nodes.put(key, node);
		}
		node.usedFrame = frame;
		node.distance = distance;
		if (node.version != world.version(level, nx, nz)) {
			// Flying, every chunk that leaves changes the nodes along the trail: one rebuild per while, not per chunk.
			if (node.version < 0) request(level, nx, nz, distance);
			else if (now - node.builtAt > REBUILD_AFTER << Math.min(level, 3)) request(level, nx, nz, distance + STALE_PENALTY);
		}
		if (node.quads > 0 && node.array != 0) draws.add(node);
	}

	private void request(int level, int nx, int nz, double priority) {
		long key = LodWorld.key(level, nx, nz);
		LodWorker.Request r = worker.requests.get(key);
		if (r == null || r.world != world) {
			if (r != null && r.taken.get()) return;
			r = new LodWorker.Request(world, level, nx, nz);
			worker.requests.put(key, r);
			worker.wake();
		}
		r.priority = priority;
		r.frame = frame;
	}

	/** Horizontal distance from the camera to a square. */
	private double distance(int x0, int z0, int size) {
		double dx = Math.max(0, Math.max(x0 - camX, camX - (x0 + size)));
		double dz = Math.max(0, Math.max(z0 - camZ, camZ - (z0 + size)));
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** Horizontal distance from the camera to a square's furthest corner. */
	private double farthest(int x0, int z0, int size) {
		double dx = Math.max(Math.abs(x0 - camX), Math.abs(x0 + size - camX));
		double dz = Math.max(Math.abs(z0 - camZ), Math.abs(z0 + size - camZ));
		return Math.sqrt(dx * dx + dz * dz);
	}

	private boolean overlapsSquare(int x0, int z0, int size) {
		if (!hasSquare) return false;
		int a = x0 >> 4, b = (x0 + size - 1) >> 4, c = z0 >> 4, d = (z0 + size - 1) >> 4;
		return b >= vMinX && a <= vMaxX && d >= vMinZ && c <= vMaxZ;
	}

	/** Whether the game draws every chunk of the square, all of its land. */
	private boolean covered(int x0, int z0, int size) {
		if (!hasSquare) return false;
		int a = x0 >> 4, b = (x0 + size - 1) >> 4, c = z0 >> 4, d = (z0 + size - 1) >> 4;
		if (a < vMinX || b > vMaxX || c < vMinZ || d > vMaxZ) return false;
		for (int z = c; z <= d; z++) {
			for (int x = a; x <= b; x++) {
				if (mask[(z & 255) << 8 | (x & 255)] != 1) return false;
			}
		}
		return true;
	}

	// ---- The mask ----

	/**
	 * Which chunks the game draws. Asking the game is slow (a cache miss or more a chunk, 4,800 chunks at render distance
	 * 32: a fifth of the render thread when all were asked every frame), so it's asked where something changed: a chunk
	 * whose sections got or lost a mesh ({@link #sectionChanged}) every frame until it has one or its time is up; a chunk
	 * that unloads is cleared at once ({@link #chunkGone}). Every chunk is also asked one row in {@link #MASK_ROWS} a
	 * frame, and all of them on the first frame.
	 * <p>
	 * A mesh isn't enough: the game draws only the chunks inside a circle around the camera's chunk (and FpsTarget may
	 * draw fewer). It keeps meshes outside it (and chunks loaded around it), and where neither drew them they showed as
	 * gaps between the game's chunks and the far terrain.
	 */
	private void updateMask(LevelRenderer levelRenderer, ClientLevel level, int cx, int cz, int rd) {
		int r = rd + 2;
		int minX = cx - r, maxX = cx + r, minZ = cz - r, maxZ = cz + r;
		if (hasSquare && (minX != vMinX || minZ != vMinZ || maxX != vMaxX || maxZ != vMaxZ)) {
			for (int z = vMinZ; z <= vMaxZ; z++) {
				for (int x = vMinX; x <= vMaxX; x++) {
					if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) continue;
					int i = (z & 255) << 8 | (x & 255);
					compiled[i] = top[i] = ground[i] = 0;
					copied.remove(chunkKey(x, z));
					if (mask[i] != 0) {
						mask[i] = 0;
						maskChanged = true;
					}
				}
			}
		}
		vMinX = minX;
		vMaxX = maxX;
		vMinZ = minZ;
		vMaxZ = maxZ;
		boolean first = !hasSquare;
		hasSquare = true;
		// 1.21.1 draws every section of a chunk it draws, top to bottom.
		bandLo = 0;
		bandHi = Mth.clamp(world.height >> 4, 0, 255);
		int cap = FpsTarget.drawCap();
		boolean moved = rd != viewDistance || cx != viewX || cz != viewZ || cap != viewCap;
		viewDistance = rd;
		viewX = cx;
		viewZ = cz;
		viewCap = cap;
		// What the game draws moved: the mask follows from what's known of the chunks.
		if (moved && !first) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int x = minX; x <= maxX; x++) refresh(x, z);
			}
		}
		long millis = Util.getMillis();
		synchronized (changedChunks) {
			for (int k = 0; k < changedChunks.size(); k++) watched.put(changedChunks.getLong(k), millis + WATCH_FOR);
			changedChunks.clear();
		}
		for (ObjectIterator<Long2LongMap.Entry> it = watched.long2LongEntrySet().fastIterator(); it.hasNext(); ) {
			Long2LongMap.Entry e = it.next();
			int x = (int) (e.getLongKey() >> 32), z = (int) e.getLongKey();
			if (x < minX || x > maxX || z < minZ || z > maxZ || ask(levelRenderer, level, x, z) || millis > e.getLongValue()) it.remove();
		}
		int z0 = first ? minZ : minZ + Math.floorMod(frame - minZ, MASK_ROWS), step = first ? 1 : MASK_ROWS;
		for (int z = z0; z <= maxZ; z += step) {
			for (int x = minX; x <= maxX; x++) ask(levelRenderer, level, x, z);
		}
	}

	/** Asks the game whether it has a mesh for the chunk, into the mask; true if it has. */
	private boolean ask(LevelRenderer levelRenderer, ClientLevel level, int x, int z) {
		byte has = 0;
		int i = (z & 255) << 8 | (x & 255);
		LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
		if (chunk != null) {
			Heightmap.Types surface = Heightmap.Types.WORLD_SURFACE;
			int y = chunk.getHeight(surface, 8, 8);
			int low = Math.min(Math.min(y, Math.min(chunk.getHeight(surface, 0, 0), chunk.getHeight(surface, 15, 0))),
					Math.min(chunk.getHeight(surface, 0, 15), chunk.getHeight(surface, 15, 15)));
			top[i] = (byte) Mth.clamp(chunk.getHighestFilledSectionIndex() + 1, 0, 255);
			ground[i] = (byte) Mth.clamp((low - world.minY) >> 4, 0, 255);
			y = Mth.clamp(y, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
			// The section at its surface. The game's grid of sections wraps around, but only past the circle it draws,
			// where the mask has 0 anyway.
			if (levelRenderer.isSectionCompiled(probe.set(x * 16 + 8, y, z * 16 + 8))) has = 1;
		}
		compiled[i] = has;
		refresh(x, z);
		// Loaded, and the far terrain shows (some of) it: copied now, not when it unloads. A shader pack may show it under the
		// game's chunks too.
		// Near the edge of the game's circle too: the far terrain has it ready when the player moves and the game lets go of it.
		if (chunk != null && (mask[i] != 1 || forPack || !deepInside(x, z))) {
			long key = chunkKey(x, z);
			if (!copied.contains(key)) toCopy.add(key);
		}
		return has != 0;
	}

	/**
	 * The mask's value for a chunk: 0 where the game doesn't draw it, 1 where it draws all its land, 2 where some of it
	 * is above or below the sections the game draws (the far terrain shows that part).
	 */
	private int maskValue(int i, int x, int z) {
		if (compiled[i] == 0 || !inCircle(x, z)) return 0;
		return (top[i] & 255) > bandHi || (ground[i] & 255) < bandLo ? 2 : 1;
	}

	/** How many chunks in from the edge of the game's circle the far terrain is kept ready under it. */
	private static final int EDGE = 3;

	/** Whether the chunk is more than EDGE chunks inside the circle the game draws. */
	private boolean deepInside(int x, int z) {
		return ChunkTrackingView.isInViewDistance(viewX, viewZ, viewDistance - EDGE, x, z) && FpsTarget.drawnWithin(viewX, viewZ, x, z, EDGE);
	}

	/** Whether every chunk of a square is more than EDGE chunks inside the circle the game draws. */
	private boolean deepInside(int x0, int z0, int size) {
		int a = x0 >> 4, b = (x0 + size - 1) >> 4, c = z0 >> 4, d = (z0 + size - 1) >> 4;
		return deepInside(a, c) && deepInside(a, d) && deepInside(b, c) && deepInside(b, d);
	}

	/** Whether the game draws chunks there: the same test as its own, and FpsTarget's. */
	private boolean inCircle(int x, int z) {
		return ChunkTrackingView.isInViewDistance(viewX, viewZ, viewDistance, x, z) && FpsTarget.drawn(viewX, viewZ, x, z);
	}

	private void refresh(int x, int z) {
		int i = (z & 255) << 8 | (x & 255);
		byte value = (byte) maskValue(i, x, z), old = mask[i];
		if (old == value) return;
		mask[i] = value;
		maskChanged = true;
		if (value != 1 && old != 0) {
			// The far terrain shows more of it now; it may have changed while the game drew it.
			long key = chunkKey(x, z);
			copied.remove(key);
			toCopy.add(key);
		}
	}

	/** Copies a few loaded chunks the far terrain shows: it has nothing for them until they unload otherwise. */
	private void copyLoaded(ClientLevel level) {
		for (int n = 0; n < COPIES_PER_FRAME && !toCopy.isEmpty() && worker.hasRoom(); n++) {
			long key = toCopy.removeFirstLong();
			int x = (int) (key >> 32), z = (int) key;
			if (x < vMinX || x > vMaxX || z < vMinZ || z > vMaxZ || copied.contains(key)) continue;
			LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
			if (chunk == null || !Lod.lit(level, chunk)) continue;
			worker.add(new LodSnapshot(world, level, chunk));
			copied.add(key);
		}
	}

	private static long chunkKey(int x, int z) {
		return (long) x << 32 | z & 0xFFFFFFFFL;
	}

	/** One of the game's sections in this chunk got a mesh or lost it; any thread. */
	void sectionChanged(int x, int z) {
		synchronized (changedChunks) {
			if (changedChunks.size() < 1 << 16) changedChunks.add(chunkKey(x, z));
		}
	}

	/** A chunk the game let go of: far terrain shows there again right away. */
	void chunkGone(int x, int z) {
		long key = chunkKey(x, z);
		copied.remove(key);
		toCopy.remove(key);
		if (!hasSquare || x < vMinX || x > vMaxX || z < vMinZ || z > vMaxZ) return;
		int i = (z & 255) << 8 | (x & 255);
		compiled[i] = 0;
		if (mask[i] != 0) {
			mask[i] = 0;
			maskChanged = true;
		}
	}

	// ---- Meshes ----

	private void receive() {
		long budget = UPLOAD_BUDGET;
		boolean any = false;
		try {
			while (budget > 0) {
				LodWorker.Result result = worker.results.poll();
				if (result == null) break;
				LodMesher.Mesh mesh = result.mesh();
				try {
					if (result.request().world == world) {
						budget -= upload(mesh);
						any = true;
					}
				} finally {
					mesh.free();
				}
			}
		} finally {
			if (any) {
				GlStateManager._glBindVertexArray(0);
				GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, 0);
				BufferUploader.invalidate();
			}
		}
	}

	private long upload(LodMesher.Mesh mesh) {
		long key = LodWorld.key(mesh.level(), mesh.nx(), mesh.nz());
		Node node = nodes.get(key);
		if (node == null) {
			node = new Node(mesh.level(), mesh.nx(), mesh.nz());
			node.usedFrame = frame;
			nodes.put(key, node);
		}
		node.close();
		int scale = 1 << mesh.level();
		node.version = mesh.version();
		node.builtAt = now;
		node.quads = mesh.quads();
		node.waterQuads = mesh.waterQuads();
		node.minY = world.minY + mesh.minY() * scale;
		node.maxY = world.minY + mesh.maxY() * scale;
		if (mesh.quads() == 0) return 0;
		long bytes = (long) mesh.quads() * 4 * VERTEX_BYTES;
		ByteBuffer data = MemoryUtil.memByteBuffer(mesh.vertices(), (int) bytes);
		node.buffer = GlStateManager._glGenBuffers();
		node.array = GlStateManager._glGenVertexArrays();
		GlStateManager._glBindVertexArray(node.array);
		GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, node.buffer);
		GlStateManager._glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);
		GlStateManager._enableVertexAttribArray(0);
		GlStateManager._vertexAttribIPointer(0, 1, GL_UNSIGNED_INT, VERTEX_BYTES, 0);
		GlStateManager._enableVertexAttribArray(1);
		GlStateManager._vertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, true, VERTEX_BYTES, 4);
		return bytes;
	}

	private void evict() {
		int before = frame - KEEP_FRAMES;
		var it = nodes.values().iterator();
		while (it.hasNext()) {
			Node n = it.next();
			if (n.usedFrame < before) {
				n.close();
				it.remove();
			}
		}
	}

	/** Frees everything; the renderer can't be used after. */
	void close() {
		if (closed) return;
		closed = true;
		for (Node n : nodes.values()) n.close();
		nodes.clear();
		draws.clear();
		worker.requests.values().removeIf(r -> r.world == world && !r.taken.get());
		if (maskTexture != 0) glDeleteTextures(maskTexture);
		if (maskBuffer != 0) GlStateManager._glDeleteBuffers(maskBuffer);
		maskBuffer = maskTexture = 0;
		MemoryUtil.memFree(maskData);
	}
}
