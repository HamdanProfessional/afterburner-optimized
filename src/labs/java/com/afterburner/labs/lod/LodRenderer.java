package com.afterburner.labs.lod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * Draws one world's far terrain: picks the nodes to show (finer near the player, coarser further out, never coarse
 * ones over the chunks the game draws itself), asks the worker for their meshes, uploads what it built, and draws them
 * in the main pass right after the solid terrain. Render thread only.
 * <p>
 * Where the game's own chunks are, the vertex shader hides the far terrain chunk by chunk: a mask of 256 x 256 chunks
 * (wrapping around) says which ones the game draws, and quads at levels 0 and 1 never cross a chunk's border.
 * <p>
 * Drawn by a shader pack instead (its dh_terrain, see Lod), it has the pack's projection for it, and no mask: the game's
 * chunks are drawn over it, and the pack hides it near the player itself; under the game's chunks near their edge it's kept,
 * for packs that fade those out there. A pack with dh_water draws the water on its own, see-through, after the rest.
 */
final class LodRenderer {
	private static final Logger LOGGER = LoggerFactory.getLogger("afterburner");
	static final VertexFormat FORMAT = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.R32_UINT)
			.addAttribute("Color", GpuFormat.RGBA8_UNORM)
			.build();
	private static final int VERTEX_BYTES = 8;
	/** A node is split into finer ones while the player is nearer than this many of its voxels. */
	private static final int DETAIL = 256;
	private static final long UPLOAD_BUDGET = 4L << 20;
	/** A built node whose chunks changed is rebuilt at most this often (nanoseconds, doubling per level up to 16 s). */
	private static final long REBUILD_AFTER = 2_000_000_000L;
	/** Added to a rebuild's priority, so places with nothing drawn yet come first. */
	private static final double STALE_PENALTY = 1e6;
	private static final int KEEP_FRAMES = 600, MASK = 256;
	/** Every chunk is asked again one row in this many per frame, in case a change got past {@link #sectionChanged}. */
	private static final int MASK_ROWS = 64;
	/** Loaded chunks copied for the far terrain per frame at most ({@link #copyLoaded}). */
	private static final int COPIES_PER_FRAME = 4;
	/** How long past its fade-in a chunk whose sections changed is asked every frame (ms). */
	private static final long WATCH_AFTER_FADE = 1000;
	/**
	 * With a shader pack, nodes the game's chunks cover are kept from this far out (of the render distance): Complementary
	 * hides the far terrain nearer than 0.4 of it, MakeUp fades the game's chunks out from 0.9.
	 */
	private static final double PACK_UNDER = 0.35;
	/** LodView: DrawnY (ivec4), then the shader pack's projection (mat4). */
	private static final int VIEW_BYTES = 80;
	private static @Nullable RenderPipeline pipeline, waterPipeline, floorPipeline;
	private static boolean failed;
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
		@Nullable GpuBuffer vertices, info;

		Node(int level, int nx, int nz) {
			this.level = level;
			this.nx = nx;
			this.nz = nz;
		}

		void close() {
			if (vertices != null) vertices.close();
			if (info != null) info.close();
			vertices = info = null;
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
	private final ByteBuffer infoData = MemoryUtil.memAlloc(32);
	private final ByteBuffer viewData = MemoryUtil.memCalloc(VIEW_BYTES);
	private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
	private @Nullable GpuBuffer maskBuffer, viewBuffer;
	private boolean maskChanged = true, viewChanged = true, closed;
	/** The game's draw distance in chunks and the camera's chunk, as the mask was made for. */
	private int viewDistance = -1, viewX, viewZ;
	/**
	 * The sections the game draws, from the world's bottom one (first, and one past the last): only as many above and
	 * below the camera's as its draw distance. Outside them the far terrain shows even where the game draws the chunk.
	 */
	private int bandLo, bandHi;
	/** Loaded chunks copied for the far terrain (the game's own copy comes when a chunk unloads), and those to copy. */
	private final LongOpenHashSet copied = new LongOpenHashSet();
	private final LongLinkedOpenHashSet toCopy = new LongLinkedOpenHashSet();
	private int frame, maxQuads, lodBlocks;
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
	private @Nullable Frustum frustum;
	/** Drawn by a shader pack this frame. */
	private boolean forPack;

	LodRenderer(LodWorld world, LodWorker worker) {
		this.world = world;
		this.worker = worker;
	}

	static RenderPipeline pipeline() {
		if (pipeline == null) {
			pipeline = terrain("far_terrain")
					.withDepthStencilState(DepthStencilState.DEFAULT)
					// The main pass's one color target, like the solid terrain's.
					.withColorTargetState(ColorTargetState.DEFAULT)
					.build();
		}
		return pipeline;
	}

	/** The water, for a shader pack's dh_water: blended like the game's translucent terrain. Only packs draw with it. */
	static RenderPipeline waterPipeline() {
		if (waterPipeline == null) {
			waterPipeline = terrain("far_water")
					.withDepthStencilState(DepthStencilState.DEFAULT)
					.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
					.build();
		}
		return waterPipeline;
	}

	private static RenderPipeline.Builder terrain(String name) {
		Identifier shader = Identifier.fromNamespaceAndPath("afterburner", "core/far_terrain");
		return RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
				.withLocation(Identifier.fromNamespaceAndPath("afterburner", "pipeline/" + name))
				.withBindGroupLayout(BindGroupLayouts.FOG)
				.withBindGroupLayout(BindGroupLayouts.SAMPLER2)
				.withBindGroupLayout(BindGroupLayouts.PROJECTION)
				.withBindGroupLayout(BindGroupLayouts.TERRAIN_INFO)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform("LodNode", UniformType.UNIFORM_BUFFER)
						.withUniform("LodView", UniformType.UNIFORM_BUFFER)
						.withUniform("LodMask", UniformType.TEXEL_BUFFER, GpuFormat.R8_UINT)
						.build())
				.withVertexShader(shader)
				.withFragmentShader(shader)
				.withVertexBinding(0, FORMAT)
				.withPrimitiveTopology(PrimitiveTopology.QUADS);
	}

	/**
	 * Under a shader pack's water, the depth of a floor (far_water_floor.fsh): the far terrain keeps no ground under its
	 * water, and packs see through water to what's under it. Depth only, with the pack's depth (nearer is less).
	 */
	static RenderPipeline floorPipeline() {
		if (floorPipeline == null) {
			Identifier shader = Identifier.fromNamespaceAndPath("afterburner", "core/far_water_floor");
			floorPipeline = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
					.withLocation(Identifier.fromNamespaceAndPath("afterburner", "pipeline/far_water_floor"))
					.withBindGroupLayout(BindGroupLayouts.TERRAIN_INFO)
					.withBindGroupLayout(BindGroupLayout.builder()
							.withUniform("LodNode", UniformType.UNIFORM_BUFFER)
							.withUniform("LodView", UniformType.UNIFORM_BUFFER)
							.build())
					.withVertexShader(shader)
					.withFragmentShader(shader)
					.withVertexBinding(0, FORMAT)
					.withPrimitiveTopology(PrimitiveTopology.QUADS)
					.withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN, true))
					.build();
		}
		return floorPipeline;
	}

	/**
	 * Before the main pass: uploads, picks the nodes, updates the mask. {@code packProjection} is the shader pack's for it, when
	 * one draws it.
	 */
	void prepare(Minecraft mc, LevelRenderer levelRenderer, ClientLevel level, CameraRenderState camera, @Nullable Matrix4fc packProjection) {
		frame++;
		now = System.nanoTime();
		draws.clear();
		maxQuads = 0;
		if (failed || closed) return;
		receive();
		Vec3 pos = camera.pos;
		camX = pos.x;
		camZ = pos.z;
		forPack = packProjection != null;
		if (packProjection != null) {
			// The game's frustum ends at its own far plane, not moved out with a pack.
			frustum = new Frustum(camera.viewRotationMatrix, new Matrix4f(packProjection));
			frustum.prepare(pos.x, pos.y, pos.z);
		} else {
			frustum = camera.cullFrustum;
		}
		lodBlocks = LodSettings.drawn() * 16;
		int rd = mc.options.getEffectiveRenderDistance();
		int cx = Mth.floor(pos.x) >> 4, cz = Mth.floor(pos.z) >> 4;
		updateMask(mc, levelRenderer, level, cx, Mth.floor(pos.y) >> 4, cz, rd);
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

		if (maxQuads > 0) RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(maxQuads * 6);
		if (maskBuffer == null) {
			maskBuffer = RenderSystem.getDevice().createBuffer(() -> "Afterburner far terrain mask",
					GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, MASK * MASK);
			maskChanged = true;
		}
		if (maskChanged) {
			maskData.clear();
			maskData.put(mask).flip();
			RenderSystem.getDevice().createCommandEncoder().writeToBuffer(maskBuffer.slice(), maskData);
			maskChanged = false;
		}
		if (viewBuffer == null) {
			viewBuffer = RenderSystem.getDevice().createBuffer(() -> "Afterburner far terrain view",
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, VIEW_BYTES);
			viewChanged = true;
		}
		if (viewChanged || packProjection != null) {
			viewData.clear();
			viewData.putInt(world.minY + bandLo * 16).putInt(world.minY + bandHi * 16).putInt(0).putInt(0);
			if (packProjection != null) packProjection.get(16, viewData);
			viewData.clear();
			RenderSystem.getDevice().createCommandEncoder().writeToBuffer(viewBuffer.slice(), viewData);
			viewChanged = false;
		}
		// With a pack it's drawn with the pack's pipeline for it.
		if (!forPack && !draws.isEmpty() && RenderSystem.getCompiledPipelineNullable(pipeline()) == null) {
			LOGGER.error("[Afterburner] The far terrain shader didn't load; far terrain is off until restart");
			failed = true;
			draws.clear();
		}
	}

	/** Whether there's anything to draw this frame. */
	boolean hasDraws() {
		return !draws.isEmpty() && maskBuffer != null && viewBuffer != null;
	}

	/** Whether any of it is water this frame. */
	boolean hasWater() {
		if (!hasDraws()) return false;
		for (Node n : draws) {
			if (n.waterQuads > 0) return true;
		}
		return false;
	}

	/** In the main pass, after the solid terrain. */
	void draw(RenderPass pass, GpuBufferSlice terrainUniform) {
		CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(pipeline());
		if (compiled != null) drawNodes(pass, compiled, terrainUniform, true, ALL);
	}

	/**
	 * In a shader pack's pass for it, with the pack's pipeline: its other uniforms are bound. {@code part}: {@link #ALL},
	 * {@link #SOLID} or {@link #WATER}.
	 */
	void drawWith(RenderPass pass, CompiledRenderPipeline compiled, GpuBufferSlice terrainUniform, int part) {
		drawNodes(pass, compiled, terrainUniform, false, part);
	}

	/**
	 * {@code game}: with the game's own pipeline (far_terrain.vsh), which hides it under the game's chunks with the mask.
	 * {@code part}: what of each node.
	 */
	private void drawNodes(RenderPass pass, CompiledRenderPipeline compiled, GpuBufferSlice terrainUniform, boolean game, int part) {
		if (!hasDraws()) return;
		pass.pushDebugGroup(() -> "Afterburner far terrain");
		// Closed even if a draw throws: a group left open crashes the game when the pass ends.
		try {
			pass.setPipeline(compiled);
			pass.setUniform("TerrainUniform", terrainUniform);
			pass.setUniform("Sampler2", Minecraft.getInstance().gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			if (game) pass.setUniform("LodMask", maskBuffer);
			pass.setUniform("LodView", viewBuffer);
			RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
			pass.setIndexBuffer(indices.getBuffer(), indices.type());
			for (Node n : draws) {
				if (n.vertices == null || n.info == null) continue;
				// The water's quads are last.
				int solid = n.quads - n.waterQuads;
				int count = part == ALL ? n.quads : part == SOLID ? solid : n.waterQuads;
				if (count == 0) continue;
				pass.setUniform("LodNode", n.info);
				pass.setVertexBuffer(0, n.vertices.slice());
				pass.drawIndexed(count * 6, 1, part == WATER ? solid * 6 : 0, 0, 0);
			}
		} finally {
			pass.popDebugGroup();
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
		if (level <= 1 && covered(x0, z0, size) && (!forPack || farthest(x0, z0, size) < PACK_UNDER * viewDistance * 16)) {
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
		if (node.quads > 0 && node.vertices != null) {
			draws.add(node);
			maxQuads = Math.max(maxQuads, node.quads);
		}
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
	 * whose sections got or lost a mesh ({@link #sectionChanged}) every frame until it has one (a section is drawn once
	 * it's faded in a bit, so this takes a few frames) or its time is up; a chunk that unloads is cleared at once
	 * ({@link #chunkGone}). Every chunk is also asked one row in {@link #MASK_ROWS} a frame, and all of them on the
	 * first frame.
	 * <p>
	 * A mesh isn't enough: the game draws only the chunks inside a circle around the camera's chunk, and only the
	 * sections so many above and below the camera's. It keeps meshes outside those (and chunks loaded around them), and
	 * where neither drew them they showed as gaps between the game's chunks and the far terrain.
	 */
	private void updateMask(Minecraft mc, LevelRenderer levelRenderer, ClientLevel level, int cx, int cy, int cz, int rd) {
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
		int minSection = world.minY >> 4;
		int lo = Mth.clamp(cy - rd - minSection, 0, 255), hi = Mth.clamp(cy + rd + 1 - minSection, 0, 255);
		boolean moved = false;
		if (lo != bandLo || hi != bandHi) {
			bandLo = lo;
			bandHi = hi;
			viewChanged = moved = true;
		}
		if (rd != viewDistance || cx != viewX || cz != viewZ) {
			viewDistance = rd;
			viewX = cx;
			viewZ = cz;
			moved = true;
		}
		// What the game draws moved: the mask follows from what's known of the chunks.
		if (moved && !first) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int x = minX; x <= maxX; x++) refresh(x, z);
			}
		}
		long fade = Util.toMillis(mc.options.chunkSectionFadeInTime().get());
		long millis = Util.getMillis();
		synchronized (changedChunks) {
			for (int k = 0; k < changedChunks.size(); k++) watched.put(changedChunks.getLong(k), millis + fade + WATCH_AFTER_FADE);
			changedChunks.clear();
		}
		for (ObjectIterator<Long2LongMap.Entry> it = watched.long2LongEntrySet().fastIterator(); it.hasNext(); ) {
			Long2LongMap.Entry e = it.next();
			int x = (int) (e.getLongKey() >> 32), z = (int) e.getLongKey();
			if (x < minX || x > maxX || z < minZ || z > maxZ || ask(levelRenderer, level, x, z, fade) || millis > e.getLongValue()) it.remove();
		}
		int z0 = first ? minZ : minZ + Math.floorMod(frame - minZ, MASK_ROWS), step = first ? 1 : MASK_ROWS;
		for (int z = z0; z <= maxZ; z += step) {
			for (int x = minX; x <= maxX; x++) ask(levelRenderer, level, x, z, fade);
		}
	}

	/** Asks the game whether it has a mesh for the chunk (in a section it draws), into the mask; true if it has. */
	private boolean ask(LevelRenderer levelRenderer, ClientLevel level, int x, int z, long fade) {
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
			y = Math.min(Math.max(y, world.minY + bandLo * 16), world.minY + bandHi * 16 - 1);
			y = Mth.clamp(y, level.getMinY(), level.getMaxY());
			if (levelRenderer.isSectionCompiledAndVisible(probe.set(x * 16 + 8, y, z * 16 + 8), fade)) has = 1;
		}
		compiled[i] = has;
		refresh(x, z);
		// Loaded, and the far terrain shows (some of) it: copied now, not when it unloads. A shader pack may show it under the
		// game's chunks too.
		if (chunk != null && (mask[i] != 1 || forPack)) {
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

	/** Whether the game draws chunks there: the same test as its own. */
	private boolean inCircle(int x, int z) {
		return ChunkTrackingView.isInViewDistance(viewX, viewZ, viewDistance, x, z);
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
		while (budget > 0) {
			LodWorker.Result result = worker.results.poll();
			if (result == null) break;
			LodMesher.Mesh mesh = result.mesh();
			try {
				if (result.request().world == world) budget -= upload(mesh);
			} finally {
				mesh.free();
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
		node.vertices = RenderSystem.getDevice().createBuffer(() -> "Afterburner far terrain", GpuBuffer.USAGE_VERTEX, data);
		int size = LodMesher.NODE * scale;
		infoData.clear();
		infoData.putInt(mesh.nx() * size).putInt(world.minY).putInt(mesh.nz() * size).putInt(scale);
		infoData.putInt(mesh.level() <= 1 ? 1 : 0).putInt(0).putInt(0).putInt(0);
		infoData.flip();
		node.info = RenderSystem.getDevice().createBuffer(() -> "Afterburner far terrain node", GpuBuffer.USAGE_UNIFORM, infoData);
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
		if (maskBuffer != null) maskBuffer.close();
		if (viewBuffer != null) viewBuffer.close();
		maskBuffer = viewBuffer = null;
		MemoryUtil.memFree(maskData);
		MemoryUtil.memFree(infoData);
		MemoryUtil.memFree(viewData);
	}
}
