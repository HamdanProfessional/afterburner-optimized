package com.afterburner.client.render;

import com.afterburner.Afterburner;
import com.afterburner.Features;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Draws the chunk sections of each region ({@link ChunkRegions}) and layer with one multi-draw instead of vanilla's
 * draw call, uniform upload and vertex array switch per section and layer, which is what holds 1.21.1's frame rate down.
 * <p>
 * The meshes of a region's sections share one vertex buffer (and translucent ones one index buffer), with their positions
 * moved from the section's corner to the region's. Meshes go in when vanilla would upload them into the section's own
 * vertex buffer, on the render thread like vanilla's uploads, and are dropped there if their build was cancelled since.
 * The rest of the conversion (moving positions, {@link SectionFaces}, {@link CompactVertices}) is done before, on the
 * section builder's thread.
 * <p>
 * What a layer draws is kept between frames ({@link LayerFrame}), like the 26.3 batcher's frames: the sections with
 * something to draw are only looked up again when a section actually got a new mesh or the list of visible sections
 * changed, and each draw remembers where the camera may go without its face groups changing, so the batches are only
 * filled again where something actually changed. A section's mesh is never re-stored or re-uploaded by this.
 * <p>
 * Order is kept where it matters: regions are drawn in the order of their nearest section (near to far, like vanilla),
 * and translucent ones in the order of their farthest (far to near), each region's sections far to near.
 * <p>
 * Batching by region follows Sodium's design (CaffeineMC), built here on vanilla's renderer.
 */
public final class ChunkBatcher {
	public static final boolean ENABLED = Features.CHUNK_BATCHING.enabled();
	private static final boolean FACES = Features.FACE_CULLING.enabled();
	/** The chunk layers, in vanilla's order: solid, cutout mipped, cutout, translucent, tripwire. */
	public static final List<RenderType> LAYERS = RenderType.chunkBufferLayers();
	private static final int TRANSLUCENT_LAYER = LAYERS.indexOf(RenderType.translucent());
	/** What each layer's draws are called in the benchmark's GPU times ({@link Probe#mark}). */
	private static final String[] TIMER_LABELS = new String[LAYERS.size()];

	static {
		for (int i = 0; i < LAYERS.size(); i++) {
			RenderType type = LAYERS.get(i);
			TIMER_LABELS[i] = "terrain layer " + (type == RenderType.solid() ? "solid" : type == RenderType.cutoutMipped() ? "cutout_mipped"
					: type == RenderType.cutout() ? "cutout" : type == RenderType.translucent() ? "translucent" : type == RenderType.tripwire() ? "tripwire" : i);
		}
	}
	/** How a mesh's vertices are stored: as vanilla's, {@link CompactVertices compact}, or extended for a shader pack ({@link TerrainExtras}). */
	public static final int VANILLA = 0, COMPACT = 1, EXTENDED = 2, FORMATS = 3;
	/** Allocations in the region buffers start at multiples of this, which both vertex sizes divide. */
	private static final long ALIGN = 32;
	private static final long FIRST_VERTEX_BYTES = 256 * 1024, FIRST_INDEX_BYTES = 32 * 1024;
	/** {@code -Dafterburner.frameReuse=false} looks every section up and fills every batch again every frame, as before. */
	private static final boolean REUSE = !"false".equals(System.getProperty("afterburner.frameReuse"));
	/** Frames at most that water and glass are drawn out of the far-to-near order while the visible list keeps changing. */
	private static final int RESORT_EVERY = 30;
	/** Water and glass taken in place too (see {@link LayerFrame#prepare}): off until it draws right with shader packs. */
	private static final boolean TRANSLUCENT_IN_PLACE = Boolean.getBoolean("afterburner.translucentInPlace");
	/** {@code -Dafterburner.movingFill=false} fills every batch again whenever the camera moved, as before. */
	private static final boolean MOVING_FILL = !"false".equals(System.getProperty("afterburner.movingFill"));
	/**
	 * The wide-frustum section cache. Off by default: on 1.21.1 it misfires (a pick routinely fails to hold vanilla's
	 * sections at a base, and the fallback thrashes pick/fall every back-off cycle), and with it off the frame rate at the
	 * same spots is better. {@code -Dafterburner.wideSections=true} turns it back on for testing.
	 */
	private static final boolean WIDE = Boolean.getBoolean("afterburner.wideSections");

	/** {@code -Dafterburner.perfLog=true}: one log line every 100 frames with the frame rate and where the batcher's time goes. */
	private static final boolean PERF_LOG = Boolean.getBoolean("afterburner.perfLog");
	private static long perfFrames, perfNanos, perfPicks, perfFalls, perfLastDraws, perfLastSections, perfLastBuilds, perfLastUpdates;
	private static boolean perfAway;
	private static long perfSetup, perfPrepare, perfMark, perfFill, perfDraw, perfOcclusion, perfWide, perfWideFrames;
	/** How much wider than the camera's view the sections the frames are built from are picked, on each side. */
	private static final double WIDE_MARGIN = Math.toRadians(15.0);
	/** The widest half-angle the view is widened to (a perspective projection ends at 90 degrees). */
	private static final double WIDE_MAX_HALF = Math.toRadians(85.0);
	/** Frames vanilla's own sections are used for after the wide ones twice in a row didn't have them all. */
	private static final int WIDE_BACK_OFF = 20;
	/** A section's draws are being added to the frame that holds them ({@link BatchedSection#afterburner$frameIndex}). */
	static final int SWAPPING = -2;

	/** Draw calls and section layers drawn, for the benchmark report. Render thread only. */
	public static long drawCalls, sectionDraws, builds, updates;

	private static final Long2ObjectOpenHashMap<Region> REGIONS = new Long2ObjectOpenHashMap<>();
	/** What each layer draws, kept between frames. */
	private static final LayerFrame[] FRAMES = new LayerFrame[LAYERS.size()];
	/**
	 * Sections whose mesh changed (or was let go of) since the frames last looked, so a frame takes in only those
	 * instead of going through its whole list: one queue, drained once per frame. Any thread ({@link #changed}).
	 */
	private static final ConcurrentLinkedQueue<SectionRenderDispatcher.RenderSection> CHANGED = new ConcurrentLinkedQueue<>();
	private static final AtomicInteger changedCount = new AtomicInteger();
	private static final AtomicBoolean changedOverflow = new AtomicBoolean();
	/**
	 * Sections whose translucent indices were sorted again: only how many of a draw's indices face the camera changed
	 * ({@link TranslucentCulling}), not its place in the frame, so those just have their batch filled again.
	 */
	private static final ConcurrentLinkedQueue<SectionRenderDispatcher.RenderSection> SPLITS = new ConcurrentLinkedQueue<>();
	private static final AtomicInteger splitCount = new AtomicInteger();
	/** More changes at once than this make a frame built anew instead of one by one. */
	private static final int MAX_CHANGED = 4096;
	/** The changed sections this frame takes in. */
	private static SectionRenderDispatcher.RenderSection[] changedNow = new SectionRenderDispatcher.RenderSection[64];
	private static int changedThisFrame;
	private static boolean changedOverflowed;
	/** The re-sorted sections this frame takes in. */
	private static SectionRenderDispatcher.RenderSection[] splitsNow = new SectionRenderDispatcher.RenderSection[64];
	private static int splitsThisFrame;
	/** The visible list as it was last taken in, and its generation. */
	private static Object[] lastVisible = new Object[0];
	private static int lastVisibleCount, listGeneration;
	private static int buildStamps, listStamps;
	/**
	 * The visible-list diff of its last change, worked out once per frame in {@link #checkList} and reused by every
	 * layer's {@link LayerFrame#update}: which sections joined the list and which left it. Empty while it didn't change.
	 */
	private static SectionRenderDispatcher.RenderSection[] listAdded = new SectionRenderDispatcher.RenderSection[64];
	private static SectionRenderDispatcher.RenderSection[] listRemoved = new SectionRenderDispatcher.RenderSection[64];
	private static int listAddedCount, listRemovedCount;
	/** The stamp of the last list {@link #checkList} marked, so a section's own stamp says whether it was in it. */
	private static int lastListStamp = -1;
	/** The chunk renderer these frames belong to: a new one (a world join, leave or dimension change) drops everything. */
	private static @Nullable SectionRenderDispatcher lastDispatcher;
	/** This frame's section source: whether the frames are built from {@link #wideSections} instead of vanilla's list. */
	private static boolean frameWide;
	/** Bumped when the source or its content changed; every layer's frame is built at this version. */
	private static int frameVersion = 1, frameListVersion, frameWideVersion;
	private static int wideSourceVersion = 1;
	/** Whether this frame's chunk draws leave out hidden sections (set by the solid layer). */
	private static boolean occlusion;

	// ---- The wide section list (WideSections) ----

	/** The sections the frames are built from: those in a frustum {@link #WIDE_MARGIN} wider than the camera's. */
	private static final ObjectArrayList<SectionRenderDispatcher.RenderSection> wideSections = new ObjectArrayList<>();
	/** The occlusion graph the wide sections are picked from, and the frame's culling projection they're picked with. */
	private static @Nullable SectionOcclusionGraph occlusionGraph;
	private static final Matrix4f cullView = new Matrix4f(), cullProjection = new Matrix4f();
	private static double cullX, cullY, cullZ;
	private static boolean cullValid, frustumCaptured;
	private static boolean widePicked, wideGraph;
	private static int wideBackOff;
	/** The wide list as the last pick left it, to tell whether a new pick holds anything else. */
	private static Object[] wideLast = new Object[0];
	/** The quad index pattern (0 1 2 2 3 0, the same 4 on, ...) for meshes without sorted indices, and how many it holds. */
	private static int quadIndices, quadIndexCount;
	private static PointerBuffer offsets = MemoryUtil.memAllocPointer(256);
	private static IntBuffer counts = MemoryUtil.memAllocInt(256), baseVertices = MemoryUtil.memAllocInt(256);
	/** For short indices widened to ints. */
	private static ByteBuffer widened = MemoryUtil.memAlloc(64 * 1024);
	/** The format being drawn while a region shader is applied ({@link #VANILLA} to {@link #EXTENDED}), -1 when none. */
	private static int drawing = -1;

	private ChunkBatcher() {
	}

	/** Where a section layer's mesh is: in a region's buffers, or (region null) in the section's own vertex buffer. */
	public static final class SectionMesh {
		/** A mesh in the section's own vertex buffer. */
		static final SectionMesh OWN = new SectionMesh(null, VANILLA, 0, 0, 0, 0, -1, 0, null, null);
		final @Nullable Region region;
		final int format;
		final long vertexOffset, vertexBytes;
		final int baseVertex, indexCount;
		/** Where its sorted indices are (ints) in the region's index buffer, or -1 for the quad pattern. */
		final long indexOffset, indexBytes;
		final int @Nullable [] faces;
		/** What later re-sorts of a translucent mesh must have been made from to fit it. */
		final MeshData.@Nullable SortState sortState;
		/** Of a translucent mesh's indices, how many face the camera from where they were sorted ({@link TranslucentCulling}). */
		int frontIndices, pointOfView = TranslucentCulling.NO_PARTITION;

		SectionMesh(@Nullable Region region, int format, long vertexOffset, long vertexBytes, int baseVertex, int indexCount, long indexOffset,
				long indexBytes, int @Nullable [] faces, MeshData.@Nullable SortState sortState) {
			this.region = region;
			this.format = format;
			this.vertexOffset = vertexOffset;
			this.vertexBytes = vertexBytes;
			this.baseVertex = baseVertex;
			this.indexCount = indexCount;
			this.indexOffset = indexOffset;
			this.indexBytes = indexBytes;
			this.faces = faces;
			this.sortState = sortState;
		}

		void split(BuildStamp stamp) {
			boolean fits = stamp.split() >= 0 && stamp.frontIndices() <= indexCount;
			frontIndices = fits ? stamp.frontIndices() : 0;
			pointOfView = fits ? stamp.pointOfView() : TranslucentCulling.NO_PARTITION;
		}
	}

	/**
	 * On the section builder's thread, in place of vanilla's upload of a section layer's new mesh into the section's
	 * vertex buffer: gets the mesh ready for its region, and returns its upload, for the render thread. Null leaves the
	 * buffer to vanilla (not a section's).
	 */
	public static @Nullable Runnable prepare(MeshData mesh, VertexBuffer buffer) {
		SectionRenderDispatcher.RenderSection section = ((BatchedBuffer) buffer).afterburner$section();
		int layer = ((BatchedBuffer) buffer).afterburner$layer();
		if (section == null || layer < 0) return null;
		BuildStamp stamp = ((Stamped) mesh).afterburner$stamp();
		MeshData.DrawState state = mesh.drawState();
		int count = state.vertexCount();
		if (stamp == null || state.format() != DefaultVertexFormat.BLOCK || state.mode() != VertexFormat.Mode.QUADS || count % 4 != 0) {
			// Not from a section build this knows, or not plain block quads: it goes in the section's own buffer, as in vanilla.
			return () -> storeOwn(section, layer, mesh, buffer);
		}
		if (stamp.cancelled().get()) return mesh::close;
		long pos = stamp.section();
		int sx = SectionPos.x(pos), sy = SectionPos.y(pos), sz = SectionPos.z(pos);
		ByteBuffer vertices = mesh.vertexBuffer();
		boolean sorted = mesh.indexBuffer() != null;
		int[] runs = ((TerrainExtras.Holder) mesh).afterburner$blockRuns();
		// Sorted (translucent) quads stay in their order: their indices point at them. A block's runs go with its quads.
		int[] moved = runs != null ? new int[count / 4] : null;
		int[] faces = FACES && !sorted ? SectionFaces.sort(vertices, count, CompactVertices.BLOCK_SIZE, moved) : null;
		if (faces != null && runs != null) runs = TerrainExtras.moveRuns(runs, moved);
		int slot = ChunkRegions.slot(sx, sy, sz);
		int format = runs != null && CompactVertices.extendedAppliesTo(layer) && CompactVertices.convertExtended(vertices, count, slot, runs) ? EXTENDED
				: CompactVertices.appliesTo(layer) && CompactVertices.convert(vertices, count, slot) ? COMPACT : VANILLA;
		if (format == VANILLA) moveToRegion(vertices, count, (sx & 7) * 16, (sy & 3) * 16, (sz & 7) * 16);
		return () -> {
			try (mesh) {
				if (stamp.cancelled().get() || buffer.isInvalid()) return;
				store(section, layer, stamp, sx, sy, sz, format, vertices, count, faces, mesh.indexBuffer(), state.indexType(), state.indexCount());
			}
		};
	}

	/**
	 * The same for a translucent section's indices sorted again for where the camera is now. They replace the ones stored
	 * if they were sorted from the stored mesh's quads.
	 */
	public static @Nullable Runnable prepareIndices(ByteBufferBuilder.Result result, VertexBuffer buffer) {
		SectionRenderDispatcher.RenderSection section = ((BatchedBuffer) buffer).afterburner$section();
		int layer = ((BatchedBuffer) buffer).afterburner$layer();
		if (section == null || layer < 0) return null;
		BuildStamp stamp = ((Stamped) result).afterburner$stamp();
		return () -> {
			try (result) {
				if (stamp != null && stamp.cancelled().get() || buffer.isInvalid()) return;
				SectionMesh mesh = ((BatchedSection) section).afterburner$meshes()[layer];
				if (mesh == null) return;
				if (mesh.region == null) {
					buffer.bind();
					buffer.uploadIndexBuffer(result);
					VertexBuffer.unbind();
					return;
				}
				if (stamp == null || mesh.indexOffset < 0 || mesh.sortState != stamp.sortState()) return;
				ByteBuffer indices = result.byteBuffer();
				int n = indices.remaining() / stamp.indexType().bytes;
				if (n != mesh.indexCount) return;
				mesh.region.indices().upload(mesh.indexOffset, asInts(indices, stamp.indexType(), n));
				mesh.split(stamp);
				// The split of its indices changed: the draw keeps its place and is filled again with it.
				((BatchedSection) section).afterburner$meshChanged(layer);
				splitChanged(section);
			}
		};
	}

	/**
	 * For the benchmark report: how many regions there are, and the bytes their GPU buffers hold and use (vertices, then
	 * indices). Render thread only.
	 */
	public static long[] bufferStats() {
		long[] stats = new long[5];
		stats[0] = REGIONS.size();
		for (Region region : REGIONS.values()) {
			stats[1] += region.vertices.capacity;
			stats[2] += region.vertices.capacity - region.vertices.freeBytes();
			if (region.indices != null) {
				stats[3] += region.indices.capacity;
				stats[4] += region.indices.capacity - region.indices.freeBytes();
			}
		}
		return stats;
	}

	/** The indices of a section's layer in its region (for the benchmark): 0 if not stored, -1 if in the section's own vertex buffer. */
	public static int regionIndices(SectionRenderDispatcher.RenderSection section, int layer) {
		SectionMesh mesh = ((BatchedSection) section).afterburner$meshes()[layer];
		return mesh == null ? 0 : mesh.region == null ? -1 : mesh.indexCount;
	}

	/** Adds the section's place in its region to vanilla vertex positions. */
	private static void moveToRegion(ByteBuffer v, int vertexCount, float dx, float dy, float dz) {
		if (dx == 0 && dy == 0 && dz == 0) return;
		int end = vertexCount * CompactVertices.BLOCK_SIZE;
		for (int at = 0; at < end; at += CompactVertices.BLOCK_SIZE) {
			v.putFloat(at, v.getFloat(at) + dx);
			v.putFloat(at + 4, v.getFloat(at + 4) + dy);
			v.putFloat(at + 8, v.getFloat(at + 8) + dz);
		}
	}

	/** Render thread: puts a section layer's mesh in its region, in place of the one there was. */
	private static void store(SectionRenderDispatcher.RenderSection section, int layer, BuildStamp stamp, int sx, int sy, int sz, int format,
			ByteBuffer vertices, int vertexCount, int @Nullable [] faces, @Nullable ByteBuffer indices, VertexFormat.IndexType indexType, int indexCount) {
		// A section that moved had its build cancelled; this is for where it is.
		BlockPos origin = section.getOrigin();
		if (origin.getX() >> 4 != sx || origin.getY() >> 4 != sy || origin.getZ() >> 4 != sz) return;
		SectionMesh[] meshes = ((BatchedSection) section).afterburner$meshes();
		release(meshes, layer);
		long key = ChunkRegions.key(sx, sy, sz);
		Region region = REGIONS.get(key);
		if (region == null) REGIONS.put(key, region = new Region(key, sx >> 3 << 7, sy >> 2 << 6, sz >> 3 << 7));
		int stride = stride(format);
		long bytes = (long) vertexCount * stride;
		long at = region.vertices.alloc(bytes);
		region.vertices.upload(at, vertices.slice(0, (int) bytes));
		SectionMesh mesh;
		if (indices != null) {
			long indexBytes = indexCount * 4L;
			Arena arena = region.indices();
			long indexAt = arena.alloc(indexBytes);
			arena.upload(indexAt, asInts(indices, indexType, indexCount));
			mesh = new SectionMesh(region, format, at, bytes, (int) (at / stride), indexCount, indexAt, indexBytes, null, stamp.sortState());
			mesh.split(stamp);
		} else {
			int quads = vertexCount / 4 * 6;
			growQuadIndices(quads);
			mesh = new SectionMesh(region, format, at, bytes, (int) (at / stride), quads, -1, 0, faces, null);
		}
		meshes[layer] = mesh;
		region.meshes++;
		meshChanged(section, layer);
	}

	private static int stride(int format) {
		return format == COMPACT ? CompactVertices.SIZE : format == EXTENDED ? CompactVertices.EXTENDED_SIZE : CompactVertices.BLOCK_SIZE;
	}

	/** Render thread: a mesh that isn't block quads goes in the section's own vertex buffer, as in vanilla. */
	private static void storeOwn(SectionRenderDispatcher.RenderSection section, int layer, MeshData mesh, VertexBuffer buffer) {
		if (buffer.isInvalid()) {
			mesh.close();
			return;
		}
		SectionMesh[] meshes = ((BatchedSection) section).afterburner$meshes();
		release(meshes, layer);
		buffer.bind();
		buffer.upload(mesh);
		VertexBuffer.unbind();
		meshes[layer] = SectionMesh.OWN;
		meshChanged(section, layer);
	}

	/** Render thread: the layer's mesh changed; the next frame looks the section up again ({@link #changed}). */
	private static void meshChanged(SectionRenderDispatcher.RenderSection section, int layer) {
		((BatchedSection) section).afterburner$meshChanged(layer);
		changed(section);
	}

	/**
	 * The section got a new mesh for one of its layers (or none); any thread. The next frame takes it in in place
	 * ({@link LayerFrame#prepare}): only the sections that actually changed are looked at, not the whole list.
	 */
	public static void changed(SectionRenderDispatcher.RenderSection section) {
		if (changedCount.incrementAndGet() > MAX_CHANGED) {
			// A frame built anew handles any number of changes ({@link #takeChanged}).
			changedCount.decrementAndGet();
			changedOverflow.set(true);
			return;
		}
		CHANGED.add(section);
	}

	/**
	 * A section's translucent indices were sorted again for the camera ({@link #prepareIndices}); any thread. Its draw
	 * keeps its place in the frame: only its batch is filled again, with the new split.
	 */
	public static void splitChanged(SectionRenderDispatcher.RenderSection section) {
		if (splitCount.incrementAndGet() > MAX_CHANGED) {
			// Dropped: a frame built anew gets the new split with everything else.
			splitCount.decrementAndGet();
			changedOverflow.set(true);
			return;
		}
		SPLITS.add(section);
	}

	/** Takes the sections {@link #changed} since the last frame into {@link #changedNow}. Render thread only. */
	private static void takeChanged() {
		changedOverflowed = changedOverflow.getAndSet(false);
		int n = 0;
		SectionRenderDispatcher.RenderSection section;
		while (n < MAX_CHANGED && (section = CHANGED.poll()) != null) {
			changedCount.decrementAndGet();
			if (n == changedNow.length) changedNow = Arrays.copyOf(changedNow, n * 2);
			changedNow[n++] = section;
		}
		changedThisFrame = n;
		n = 0;
		while (n < MAX_CHANGED && (section = SPLITS.poll()) != null) {
			splitCount.decrementAndGet();
			if (n == splitsNow.length) splitsNow = Arrays.copyOf(splitsNow, n * 2);
			splitsNow[n++] = section;
		}
		splitsThisFrame = n;
	}

	/** Render thread: frees every layer of a section that is reset (moved, or its buffers let go of). Returns which layers held a mesh. */
	public static int release(SectionMesh @Nullable [] meshes) {
		if (meshes == null) return 0;
		int released = 0;
		for (int layer = 0; layer < meshes.length; layer++) {
			if (meshes[layer] == null) continue;
			released |= 1 << layer;
			release(meshes, layer);
		}
		return released;
	}

	private static void release(SectionMesh[] meshes, int layer) {
		SectionMesh mesh = meshes[layer];
		if (mesh == null) return;
		meshes[layer] = null;
		Region region = mesh.region;
		if (region == null) return;
		region.vertices.free(mesh.vertexOffset, mesh.vertexBytes);
		if (mesh.indexOffset >= 0) region.indices().free(mesh.indexOffset, mesh.indexBytes);
		if (--region.meshes == 0) {
			region.delete();
			// Only if it's still the region of that key: a new world's region may have taken its place since.
			REGIONS.remove(region.key, region);
		}
	}

	/** The indices as ints: as they are, or widened from shorts. */
	private static ByteBuffer asInts(ByteBuffer indices, VertexFormat.IndexType type, int count) {
		if (type == VertexFormat.IndexType.INT) return indices.slice(0, count * 4);
		if (widened.capacity() < count * 4) {
			MemoryUtil.memFree(widened);
			widened = MemoryUtil.memAlloc(Math.max(count * 4, widened.capacity() * 2));
		}
		for (int i = 0; i < count; i++) widened.putInt(i * 4, indices.getShort(i * 2) & 0xFFFF);
		return widened.slice(0, count * 4);
	}

	private static void growQuadIndices(int indexCount) {
		if (indexCount <= quadIndexCount) return;
		int quads = Math.max(Math.max(indexCount / 6, quadIndexCount / 6 * 2), 4096);
		IntBuffer pattern = MemoryUtil.memAllocInt(quads * 6);
		for (int q = 0; q < quads; q++) {
			int v = q * 4;
			pattern.put(v).put(v + 1).put(v + 2).put(v + 2).put(v + 3).put(v);
		}
		pattern.flip();
		if (quadIndices == 0) quadIndices = GlStateManager._glGenBuffers();
		// Not through the element array binding, which would change whichever vertex array is bound.
		GlStateManager._glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, quadIndices);
		GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, pattern, GL15C.GL_STATIC_DRAW);
		MemoryUtil.memFree(pattern);
		quadIndexCount = quads * 6;
	}

	/**
	 * The level renderer's chunk renderer for this frame. A new one (a world join or leave, a dimension change) drops
	 * everything kept of the old one: its frames, its regions and their GPU buffers can't be drawn or reached again.
	 * Render thread only; called once per drawn frame, before {@link #draw}.
	 */
	public static void world(@Nullable SectionRenderDispatcher dispatcher) {
		if (dispatcher == lastDispatcher) return;
		lastDispatcher = dispatcher;
		resetAll();
	}

	/**
	 * Every section is rebuilt and its buffers let go of (a resource reload, a render distance change, a dimension
	 * change): nothing the frames kept may be drawn any more, and the regions' GPU buffers are let go of with them.
	 * Render thread only, called before the frames of the frame that follows.
	 */
	public static void worldChanged() {
		resetAll();
	}

	private static void resetAll() {
		Arrays.fill(FRAMES, null);
		for (Region region : REGIONS.values()) region.delete();
		REGIONS.clear();
		if (lastVisibleCount > 0) Arrays.fill(lastVisible, 0, lastVisibleCount, null);
		lastVisibleCount = 0;
		listGeneration++;
		// The list diff is of the world that's gone: a new one is worked out for the first list of the new one.
		Arrays.fill(listAdded, null);
		Arrays.fill(listRemoved, null);
		listAddedCount = 0;
		listRemovedCount = 0;
		lastListStamp = -1;
		frameWide = false;
		frameVersion++;
		wideSections.clear();
		Arrays.fill(wideLast, null);
		widePicked = false;
		wideGraph = false;
		wideBackOff = 0;
		wideSourceVersion++;
		frustumCaptured = false;
		cullValid = false;
		occlusion = false;
		// Changes of the old sections are the dropped frames' own: nothing of them is taken in any more.
		CHANGED.clear();
		changedCount.set(0);
		SPLITS.clear();
		splitCount.set(0);
		changedOverflow.set(false);
		if (changedThisFrame > 0) Arrays.fill(changedNow, 0, changedThisFrame, null);
		if (splitsThisFrame > 0) Arrays.fill(splitsNow, 0, splitsThisFrame, null);
		changedThisFrame = 0;
		splitsThisFrame = 0;
		changedOverflowed = false;
	}

	/** The occlusion graph the wide sections are picked from; called once per drawn frame. */
	public static void occlusionGraph(@Nullable SectionOcclusionGraph graph) {
		occlusionGraph = graph;
	}

	/** GameRenderer prepared the frustum the sections are culled with; the wide one is made from it. Render thread only. */
	public static void cullFrustum(double x, double y, double z, Matrix4f view, Matrix4f projection) {
		cullX = x;
		cullY = y;
		cullZ = z;
		cullView.set(view);
		cullProjection.set(projection);
		cullValid = true;
	}

	/** The graph tells vanilla to pick its visible sections again: the wide ones are picked again with it. */
	public static void wideGraphChanged() {
		wideGraph = true;
	}

	/** The frame is drawn from the frozen frustum of a captured screenshot: vanilla's sections, as a wide pick wouldn't hold them. */
	public static void frustumCaptured(boolean captured) {
		frustumCaptured = captured;
	}

	/**
	 * In place of vanilla's loop over the visible sections in {@code renderSectionLayer}, once the layer's render state is
	 * set up: draws them a region at a time, from the frame the layer kept of the last frame where nothing changed.
	 * Render thread only.
	 */
	public static void draw(RenderType type, ObjectArrayList<SectionRenderDispatcher.RenderSection> visible, double camX, double camY, double camZ,
			Matrix4f modelView, Matrix4f projection) {
		long perfStart = PERF_LOG ? System.nanoTime() : 0L;
		int layer = LAYERS.indexOf(type);
		if (layer < 0) return;
		boolean first = type == RenderType.solid();
		if (first && PERF_LOG) perfFrame();
		long perfT = perfStart;
		if (first) Probe.get().chunksInView(visible, camX, camY, camZ, projection, modelView);
		Probe.get().mark(TIMER_LABELS[layer]);
		if (first) {
			occlusion = OcclusionCulling.ENABLED && OcclusionCulling.beginFrame(modelView, projection, camX, camY, camZ);
			checkList(visible);
			takeChanged();
			chooseSource(visible);
			if (PERF_LOG) {
				perfSetup += System.nanoTime() - perfT;
				perfT = System.nanoTime();
				if (frameWide) {
					perfWide += wideSections.size();
					perfWideFrames++;
				}
			}
		}
		boolean occluding = first ? occlusion : OcclusionCulling.ENABLED && OcclusionCulling.on();
		LayerFrame frame = FRAMES[layer];
		if (frame == null) FRAMES[layer] = frame = new LayerFrame(layer, type);
		ObjectArrayList<SectionRenderDispatcher.RenderSection> source = frameWide ? wideSections : visible;
		frame.prepare(source, frameVersion, changedThisFrame);
		if (PERF_LOG) {
			perfPrepare += System.nanoTime() - perfT;
			perfT = System.nanoTime();
		}
		if (frameWide) {
			if (!frame.markInView(visible)) {
				// Vanilla's list has a section the wide one doesn't (turned past its margin): picked anew for the camera now.
				if (first) {
					pickWide();
					if (widePicked && wideSourceVersion != frameWideVersion) {
						// The pick holds other sections: the frames are built from it again.
						frameWideVersion = wideSourceVersion;
						frameVersion++;
						frame.prepare(wideSections, frameVersion, changedThisFrame);
					}
					if (!widePicked || !frame.markInView(visible)) {
						wideBackOff = WIDE_BACK_OFF;
						frameWide = false;
						frameVersion++;
						frame.prepare(visible, frameVersion, changedThisFrame);
						frame.drawAll();
						if (PERF_LOG) perfFalls++;
					}
				} else {
					// A layer whose frame missed what the solid layer's had: vanilla's sections for a while, all drawn now.
					wideBackOff = WIDE_BACK_OFF;
					frame.drawAll();
					if (PERF_LOG) perfFalls++;
				}
			}
		} else {
			frame.drawAll();
		}
		if (PERF_LOG) {
			perfMark += System.nanoTime() - perfT;
			perfT = System.nanoTime();
		}
		frame.fill(camX, camY, camZ, occluding);
		if (PERF_LOG) {
			perfFill += System.nanoTime() - perfT;
			perfT = System.nanoTime();
		}
		frame.draw(camX, camY, camZ, modelView, projection);
		if (PERF_LOG) perfDraw += System.nanoTime() - perfT;
		// The solid and cutout terrain is all in the depth buffer now: test the sections against it.
		if (type == RenderType.cutout()) {
			long perfO = PERF_LOG ? System.nanoTime() : 0L;
			if (occluding) OcclusionCulling.test(visible, camX, camY, camZ);
			if (PERF_LOG) perfOcclusion += System.nanoTime() - perfO;
			Probe.get().opaqueDrawn();
		}
		if (PERF_LOG) perfNanos += System.nanoTime() - perfStart;
	}

	/** Every 100 frames one line: FPS, the batcher's cost by phase, and the counters that say whether frames are being rebuilt. */
	private static void perfFrame() {
		// Frames while the window is minimised or in the background aren't play: a stretch with any of them is dropped.
		if (!Minecraft.getInstance().isWindowActive()) perfAway = true;
		if (++perfFrames % 100 != 0) return;
		if (perfAway) {
			perfAway = false;
			perfLastDraws = drawCalls;
			perfLastSections = sectionDraws;
			perfLastBuilds = ChunkBatcher.builds;
			perfLastUpdates = ChunkBatcher.updates;
			OcclusionCulling.resetStats();
			RegionFrustum.nanos = RegionFrustum.passCount = RegionFrustum.fastPasses = RegionFrustum.walked = 0;
			perfSetup = perfPrepare = perfMark = perfFill = perfDraw = perfOcclusion = perfWide = perfWideFrames = 0;
			perfNanos = 0;
			return;
		}
		long draws = drawCalls - perfLastDraws, sections = sectionDraws - perfLastSections;
		long builds = ChunkBatcher.builds - perfLastBuilds, updates = ChunkBatcher.updates - perfLastUpdates;
		perfLastDraws = drawCalls;
		perfLastSections = sectionDraws;
		perfLastBuilds = ChunkBatcher.builds;
		perfLastUpdates = ChunkBatcher.updates;
		double ms = 1e6 * 100.0;
		int rd = Minecraft.getInstance().options.getEffectiveRenderDistance();
		// The occlusion test's answers since the last line: of the sections tested, how many it found hidden.
		OcclusionCulling.measuring = true;
		long tested = OcclusionCulling.testedSections, hidden = OcclusionCulling.hiddenSections;
		OcclusionCulling.resetStats();
		Afterburner.LOGGER.info("[Afterburner] perf: {} fps rd{}, batcher {} ms [setup {}, prep {}, mark {}, fill {}, draw {}, occlusion {}], {} draws ({} sections of {} in view), wide {}, picks {}, falls {}, builds {}, updates {}, culling {} ({}% of {} hidden), view check {} ms x{} ({} fast, {} sections a pass)",
				Minecraft.getInstance().getFps(), rd, String.format("%.2f", perfNanos / ms),
				String.format("%.2f", perfSetup / ms), String.format("%.2f", perfPrepare / ms), String.format("%.2f", perfMark / ms),
				String.format("%.2f", perfFill / ms), String.format("%.2f", perfDraw / ms),
				String.format("%.2f", perfOcclusion / ms), draws / 100, sections / 100, lastVisibleCount, perfWideFrames == 0 ? 0 : perfWide / perfWideFrames, perfPicks, perfFalls, builds, updates,
				OcclusionCulling.on() ? "on" : "off", tested == 0 ? 0 : hidden * 100 / tested, tested == 0 ? 0 : tested / 100,
				String.format("%.2f", RegionFrustum.passCount == 0 ? 0.0 : RegionFrustum.nanos / 1e6 / RegionFrustum.passCount), RegionFrustum.passCount,
				RegionFrustum.fastPasses, RegionFrustum.fastPasses == 0 ? 0 : RegionFrustum.walked / RegionFrustum.fastPasses);
		RegionFrustum.fastPasses = RegionFrustum.walked = 0;
		RegionFrustum.nanos = RegionFrustum.passCount = 0;
		perfSetup = perfPrepare = perfMark = perfFill = perfDraw = perfOcclusion = perfWide = perfWideFrames = 0;
		perfNanos = 0;
	}

	/** Remembers the visible list; a new one makes every layer's frame take it in. Render thread only. */
	private static void checkList(ObjectArrayList<SectionRenderDispatcher.RenderSection> visible) {
		Object[] elements = visible.elements();
		int n = visible.size();
		if (n == lastVisibleCount && Arrays.equals(elements, 0, n, lastVisible, 0, n)) {
			listAddedCount = 0;
			listRemovedCount = 0;
			return;
		}
		// The list changed: work the join/leave diff out once here, where every layer's update reads it, instead of
		// walking the whole list (and calling into every section) again for each of the 4 or 5 layers.
		int stamp = ++listStamps;
		int added = 0, removed = 0;
		for (int k = 0; k < n; k++) {
			BatchedSection built = (BatchedSection) elements[k];
			if (built.afterburner$listStamp() != lastListStamp) {
				// Not in the list before: it joined, at its place in this list's order.
				if (added == listAdded.length) listAdded = Arrays.copyOf(listAdded, added * 2);
				listAdded[added++] = (SectionRenderDispatcher.RenderSection) elements[k];
			}
			built.afterburner$listStamp(stamp);
		}
		for (int k = 0; k < lastVisibleCount; k++) {
			BatchedSection built = (BatchedSection) lastVisible[k];
			if (built.afterburner$listStamp() != stamp) {
				// Not stamped by this list: it left.
				if (removed == listRemoved.length) listRemoved = Arrays.copyOf(listRemoved, removed * 2);
				listRemoved[removed++] = (SectionRenderDispatcher.RenderSection) lastVisible[k];
			}
		}
		listAddedCount = added;
		listRemovedCount = removed;
		lastListStamp = stamp;
		if (lastVisible.length < n) lastVisible = new Object[Math.max(n, lastVisible.length * 2)];
		System.arraycopy(elements, 0, lastVisible, 0, n);
		if (lastVisibleCount > n) Arrays.fill(lastVisible, n, lastVisibleCount, null);
		lastVisibleCount = n;
		listGeneration++;
	}

	/**
	 * Whether this frame's sections are vanilla's visible list or the wide one ({@link #wideSections}), and which version
	 * of it the frames have to be built at. Also picks the wide ones when they're new, the graph changed, or a previous
	 * pick didn't hold vanilla's sections ({@link #WIDE_BACK_OFF}).
	 */
	private static void chooseSource(ObjectArrayList<SectionRenderDispatcher.RenderSection> visible) {
		boolean wide = false;
		if (WIDE && !frustumCaptured && occlusionGraph != null && cullValid) {
			if (wideBackOff > 0) {
				wideBackOff--;
			} else {
				if (!widePicked || wideGraph) pickWide();
				wide = widePicked;
			}
		}
		if (wide != frameWide) {
			frameWide = wide;
			frameVersion++;
		}
		if (wide && wideSourceVersion != frameWideVersion) {
			frameWideVersion = wideSourceVersion;
			frameVersion++;
		}
		if (listGeneration != frameListVersion) {
			// Vanilla's list changed: frames built from it take it in; wide frames only mark what's drawn of them.
			frameListVersion = listGeneration;
			if (!wide) frameVersion++;
		}
	}

	/**
	 * Picks the sections in a frustum {@link #WIDE_MARGIN} wider than the camera's, from the occlusion graph. A pick
	 * that holds the same sections as the last one doesn't count as a new source (no frame is built again for it).
	 */
	private static void pickWide() {
		boolean wasPicked = widePicked;
		int before = wideSections.size();
		if (wideLast.length < before) wideLast = new Object[Math.max(before, wideLast.length * 2)];
		System.arraycopy(wideSections.elements(), 0, wideLast, 0, before);
		wideSections.clear();
		widePicked = false;
		wideGraph = false;
		if (cullValid && occlusionGraph != null) {
			// The culling projection is a symmetric perspective: m11 = 1/tan(vertical half-angle), m00 = m11 / aspect.
			float m00 = Math.abs(cullProjection.m00()), m11 = Math.abs(cullProjection.m11());
			if (m00 > 0.0F && m11 > 0.0F && Float.isFinite(m00) && Float.isFinite(m11)) {
				double tanHalfY = 1.0 / m11, tanHalfX = 1.0 / m00;
				double halfY = Math.atan(tanHalfY), halfX = Math.atan(tanHalfX);
				double wideY = Math.min(halfY + WIDE_MARGIN, WIDE_MAX_HALF), wideX = Math.min(halfX + WIDE_MARGIN, WIDE_MAX_HALF);
				// Widening a projection is scaling the view-space directions before it: P * diag(kx, ky, 1).
				Matrix4f wide = new Matrix4f(cullProjection)
						.mul(new Matrix4f().scale((float) (tanHalfX / Math.tan(wideX)), (float) (tanHalfY / Math.tan(wideY)), 1.0F));
				Frustum frustum = new Frustum(cullView, wide);
				frustum.prepare(cullX, cullY, cullZ);
				// Vanilla's own frustum includes the camera's cube ({@code offsetFrustum}); the wide one must hold anything it has.
				frustum.offsetToFullyIncludeCameraCube(8);
				occlusionGraph.addSectionsInFrustum(frustum, wideSections);
				widePicked = true;
			}
		}
		int n = wideSections.size();
		boolean same = wasPicked && n == before && Arrays.equals(wideSections.elements(), 0, n, wideLast, 0, before);
		if (wideLast.length < n) wideLast = new Object[Math.max(n, wideLast.length * 2)];
		System.arraycopy(wideSections.elements(), 0, wideLast, 0, n);
		if (!same) wideSourceVersion++;
		if (PERF_LOG) perfPicks++;
	}

	/**
	 * The format of the region meshes being drawn while a chunk shader is applied ({@link #VANILLA}, {@link #COMPACT},
	 * {@link #EXTENDED}), or -1 outside the batcher's draws (sections in their own buffers are vanilla's). For a shader pack's
	 * programs, which read each format's attributes.
	 */
	public static int drawingFormat() {
		return drawing;
	}

	/**
	 * For a shader pack's shadow map: draws the layer of {@code sections}, with the layer's render state set up and its
	 * shader set. Every face (the camera isn't where the shadow is seen from), no occlusion culling, translucent quads in
	 * the order they were last sorted in. Render thread only. Unused on 1.21.1 (no shader packs), kept for the pack labs.
	 */
	public static void drawShadow(RenderType type, List<SectionRenderDispatcher.RenderSection> sections, double camX, double camY, double camZ,
			Matrix4f modelView, Matrix4f projection) {
		int layer = LAYERS.indexOf(type);
		if (layer < 0) return;
		Window window = Minecraft.getInstance().getWindow();
		BufferUploader.invalidate();
		for (int format = VANILLA; format < FORMATS; format++) {
			ShaderInstance shader = format == VANILLA ? RenderSystem.getShader() : CompactVertices.shader(layer);
			if (shader == null) continue;
			drawing = format;
			shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, window);
			shader.apply();
			Uniform offset = shader.CHUNK_OFFSET;
			for (SectionRenderDispatcher.RenderSection section : sections) {
				SectionMesh mesh = ((BatchedSection) section).afterburner$meshes()[layer];
				if (mesh == null || mesh.format != format || ((BatchedSection) section).afterburner$isEmpty(layer)) continue;
				if (mesh.region == null) {
					BlockPos origin = section.getOrigin();
					if (offset != null) {
						offset.set((float) (origin.getX() - camX), (float) (origin.getY() - camY), (float) (origin.getZ() - camZ));
						offset.upload();
					}
					VertexBuffer buffer = section.getBuffer(type);
					buffer.bind();
					buffer.draw();
					drawCalls++;
					continue;
				}
				Region region = mesh.region;
				if (region.deleted) continue;
				if (offset != null) {
					offset.set((float) (region.x - camX), (float) (region.y - camY), (float) (region.z - camZ));
					offset.upload();
				}
				GlStateManager._glBindVertexArray(region.array(format, mesh.indexOffset >= 0));
				GL32C.glDrawElementsBaseVertex(GL11C.GL_TRIANGLES, mesh.indexCount, GL11C.GL_UNSIGNED_INT, Math.max(mesh.indexOffset, 0), mesh.baseVertex);
				drawCalls++;
				sectionDraws++;
			}
			if (offset != null) offset.set(0.0F, 0.0F, 0.0F);
			shader.clear();
			drawing = -1;
		}
		VertexBuffer.unbind();
	}

	/** Everything one layer draws, kept between frames. Render thread only. */
	private static final class LayerFrame {
		/** x, y, z, x + z and x - z, each low and high ({@link SectionFaces#holds}). */
		private static final int HOLDS = 10;
		/** What a draw is: every face ({@link SectionFaces}), a translucent mesh's sorted indices, plain quads, or a section's own buffer. */
		private static final int PLAIN = 0, FACES = 1, SORTED = 2, OWN = 3;

		private final int layer;
		private final RenderType type;
		/** Translucent sections must go far to near: the visible list is walked backwards. */
		private final boolean farFirst;
		/** The regions of this layer's draws, in drawing order. */
		private final ArrayList<Region> regions = new ArrayList<>();
		/** Batches to fill again at the next fill ({@link #refill}). */
		private final ArrayList<Batch> refills = new ArrayList<>();

		/** The sections with something to draw (their draws are next to each other), and their block origin. */
		SectionRenderDispatcher.RenderSection[] sections = new SectionRenderDispatcher.RenderSection[64];
		int[] sectionOrigin = new int[64 * 3], sectionFirstDraw = new int[64];
		boolean[] dead = new boolean[64];
		int sectionCount, deadCount, appendedCount;
		/** Whether this frame's sections are still the list they were built from, and the versions it saw of them. */
		boolean built;
		/** Whether the list it was built from was the wide one ({@link #wideSections}), so a change of source is built anew. */
		boolean builtWide;
		int stamp, builtSourceVersion = -1;

		/** Every section draw, in order. */
		int draws;
		Batch[] drawBatch = new Batch[256];
		SectionMesh[] drawMesh = new SectionMesh[256];
		long[] drawOffset = new long[256];
		int[] drawCount = new int[256], drawBase = new int[256], drawSection = new int[256], drawPov = new int[256];
		byte[] drawKind = new byte[256];
		boolean[] drawMoves = new boolean[256], drawAlive = new boolean[256];
		/** From each draw's last fill: where the camera may go with it adding the same ({@link SectionFaces#holds}). */
		float[] drawHolds = new float[256 * HOLDS];
		/** For the report: shown and total quads of the draw's face groups (1) or water and glass (2). */
		int[] drawShown = new int[256], drawQuads = new int[256];
		/** The sections {@link #update} is adding this frame. */
		SectionRenderDispatcher.RenderSection[] addingSections = new SectionRenderDispatcher.RenderSection[64];

		/** This frame: whether {@link OcclusionCulling} runs, and which sections are drawn before its test. */
		boolean occlusion;
		boolean[] early = new boolean[64], filledEarly = new boolean[64];
		/** Whether only the sections marked in view are drawn ({@link #markInView}), and which they are. */
		boolean filtered;
		/** The visible list the mask was made for; -1 while draws changed and it must be made again. */
		int maskedListGeneration = -1;
		boolean[] inView = new boolean[64], filledInView = new boolean[64];
		/** What the batches were last filled for, to skip filling them again for the same. */
		boolean filled, filledOcclusion, filledFiltered;
		double filledX, filledY, filledZ;
		int fillStamp;
		/** Draws of meshes in their section's own buffer (drawn as vanilla does, so looked at every fill). */
		int ownDraws;
		/** The report's counts for the batches as filled: face-group quads drawn and all of them, water and glass the same. */
		long shownFaces, allFaces, shownWater, allWater;

		/** Water and glass: frames since the draws were last in the list's order, -1 while they are ({@link #prepare}). */
		int outOfOrder = -1;

		/** Whether the visible list stopped changing: the frame goes back in order right away then. */
		private boolean settled() {
			return listAddedCount == 0 && listRemovedCount == 0;
		}

		LayerFrame(int layer, RenderType type) {
			this.layer = layer;
			this.type = type;
			this.farFirst = layer == TRANSLUCENT_LAYER;
		}

		/** Builds the frame's draws anew, or takes in what changed since the frame before. */
		void prepare(ObjectArrayList<SectionRenderDispatcher.RenderSection> source, int sourceVersion, int changes) {
			if (!built || !REUSE) {
				rebuild(source, sourceVersion);
				return;
			}
			boolean sourceChanged = builtSourceVersion != sourceVersion;
			if (!sourceChanged && changes == 0) return;
			if (changedOverflowed) {
				// More sections changed at once than can be taken in one by one: built anew.
				rebuild(source, sourceVersion);
				return;
			}
			if (farFirst) {
				// Water and glass go in the list's far-to-near order: built from it again when any of this frame's
				// sections changed. While chunks load the list changes nearly every frame, and building from the whole
				// list (20,000 sections at render distance 32) each time was the batcher's biggest cost: the change is
				// then taken in place like the other layers' (sections that came or got a new mesh drawn last for now),
				// and the frame is put back in order every RESORT_EVERY frames. Re-sorted indices only change a draw's
				// split, not its place: those are filled again.
				boolean changed = sourceChanged || anyChangedMatters(changes);
				if (!TRANSLUCENT_IN_PLACE) {
					// Built anew on every change: with a shader pack the in-place way left water stretched across the view.
					if (changed) {
						rebuild(source, sourceVersion);
						return;
					}
					if (splitsThisFrame > 0) refillSplits(splitsThisFrame);
					return;
				}
				if (changed && outOfOrder < 0) outOfOrder = 0;
				if (outOfOrder >= 0 && (++outOfOrder > RESORT_EVERY || frameWide || builtWide || !changed && outOfOrder > 1 && settled())) {
					rebuild(source, sourceVersion);
					return;
				}
				if (changed) {
					if (sourceChanged) update(source, sourceVersion, changes);
					else updateChanged(source, sourceVersion, changes);
					if (!built || builtSourceVersion != sourceVersion) return;
				}
				if (splitsThisFrame > 0) refillSplits(splitsThisFrame);
				return;
			}
			if (sourceChanged) {
				if (frameWide || builtWide) {
					// The wide list isn't the one {@link #checkList} diffed, and its changes are rare: built anew.
					rebuild(source, sourceVersion);
					return;
				}
				update(source, sourceVersion, changes);
			} else {
				updateChanged(source, sourceVersion, changes);
			}
		}

		/** Whether any of this frame's changed sections is one of this frame's ({@link #changed}). */
		private boolean anyChangedMatters(int changes) {
			for (int k = 0; k < changes; k++) {
				BatchedSection built = (BatchedSection) changedNow[k];
				if (built.afterburner$frameAt(layer) == stamp && built.afterburner$builtVersion(layer) != built.afterburner$meshVersion(layer)) return true;
			}
			return false;
		}

		/**
		 * Whether the frame holds so many left-out sections that walking past them costs more than building it anew.
		 * Sections added at the end don't count: at a long render distance chunks keep loading in at the far edge for
		 * minutes, so the end is where they go anyway, and counting them had every layer looking up all 20,000
		 * sections again every few frames (each a long frame, and most of the render thread). Water and glass are put
		 * back in order on their own ({@link #RESORT_EVERY}).
		 */
		private boolean tooMuchLeftOut() {
			return deadCount > Math.max(1024, sectionCount / 2);
		}

		/** Fills the draws of re-sorted sections again ({@link #splitChanged}); their meshes changed in place. */
		private void refillSplits(int splits) {
			for (int k = 0; k < splits; k++) {
				BatchedSection built = (BatchedSection) splitsNow[k];
				if (built.afterburner$frameAt(layer) != stamp) continue;
				int t = built.afterburner$frameIndex(layer);
				if (t < 0 || t >= sectionCount) continue;
				int first = sectionFirstDraw[t], end = t + 1 < sectionCount ? sectionFirstDraw[t + 1] : draws;
				for (int d = first; d < end; d++) {
					if (drawBatch[d] != null) refill(drawBatch[d]);
				}
			}
		}

		/** Looks up every section of the list and builds the frame's draws from them. */
		private void rebuild(ObjectArrayList<SectionRenderDispatcher.RenderSection> source, int sourceVersion) {
			builds++;
			outOfOrder = -1;
			stamp = ++buildStamps;
			Arrays.fill(sections, 0, sectionCount, null);
			Arrays.fill(drawMesh, 0, draws, null);
			Arrays.fill(drawBatch, 0, draws, null);
			sectionCount = 0;
			draws = 0;
			deadCount = 0;
			appendedCount = 0;
			ownDraws = 0;
			regions.clear();
			Object[] list = source.elements();
			int n = source.size();
			for (int k = 0; k < n; k++) {
				lookUp((SectionRenderDispatcher.RenderSection) list[farFirst ? n - 1 - k : k]);
			}
			builtSourceVersion = sourceVersion;
			built = true;
			builtWide = frameWide;
			// The draws are new: what's in view is marked again ({@link #markInView}).
			filtered = false;
			filled = false;
		}

		/**
		 * Takes in the visible list's change in place: the draws of sections that left the list are left out, the ones
		 * that joined are looked up and added at the end. The join/leave diff itself was worked out once for the frame
		 * ({@link #checkList}); this only walks the sections that actually came or went instead of the whole list, and
		 * also takes in the mesh changes of the frame's own sections. Changes to sections outside the list don't matter.
		 */
		private void update(ObjectArrayList<SectionRenderDispatcher.RenderSection> source, int sourceVersion, int changes) {
			updates++;
			int adding = 0;
			// Left the list: what the frame had of them is left out.
			for (int k = 0; k < listRemovedCount; k++) {
				BatchedSection built = (BatchedSection) listRemoved[k];
				if (built.afterburner$frameAt(layer) != stamp) continue;
				int t = built.afterburner$frameIndex(layer);
				built.afterburner$inFrame(layer, 0, -1);
				if (t >= 0 && t < sectionCount && !dead[t]) leaveOut(t);
			}
			// Joined the list: looked up and added at the end.
			for (int k = 0; k < listAddedCount; k++) {
				BatchedSection built = (BatchedSection) listAdded[k];
				if (built.afterburner$frameAt(layer) == stamp) continue;
				built.afterburner$inFrame(layer, stamp, SWAPPING);
				adding = adding(listAdded[k], adding);
			}
			// Got a new mesh: what the frame has of it is left out and it's looked up again.
			for (int k = 0; k < changes; k++) {
				SectionRenderDispatcher.RenderSection section = changedNow[k];
				BatchedSection built = (BatchedSection) section;
				if (built.afterburner$frameAt(layer) != stamp) continue;
				if (built.afterburner$builtVersion(layer) == built.afterburner$meshVersion(layer)) continue;
				int t = built.afterburner$frameIndex(layer);
				if (t == SWAPPING) continue;
				built.afterburner$inFrame(layer, stamp, SWAPPING);
				if (t >= 0) leaveOut(t);
				adding = adding(section, adding);
			}
			if (tooMuchLeftOut()) {
				// Too much would be left out: built anew.
				rebuild(source, sourceVersion);
				return;
			}
			if (adding > 0) lookUpAdded(adding);
			builtSourceVersion = sourceVersion;
			// Draws came or went: what's in view is marked again ({@link #markInView}). The mask itself stays, so
			// only the batches of the draws that came or went are filled again.
			maskedListGeneration = -1;
			if (!MOVING_FILL) filled = false;
		}

		/**
		 * Takes in the mesh changes of this frame ({@link #changed}) in place: only the sections that actually changed
		 * are looked at, never the whole list. Changes to sections outside the list don't matter here: a section is
		 * looked up when it joins the list.
		 */
		private void updateChanged(ObjectArrayList<SectionRenderDispatcher.RenderSection> source, int sourceVersion, int changes) {
			updates++;
			int adding = 0;
			for (int k = 0; k < changes; k++) {
				SectionRenderDispatcher.RenderSection section = changedNow[k];
				BatchedSection built = (BatchedSection) section;
				if (built.afterburner$frameAt(layer) != stamp) continue;
				if (built.afterburner$builtVersion(layer) == built.afterburner$meshVersion(layer)) continue;
				int t = built.afterburner$frameIndex(layer);
				if (t == SWAPPING) continue;
				built.afterburner$inFrame(layer, stamp, SWAPPING);
				if (t >= 0) leaveOut(t);
				adding = adding(section, adding);
			}
			if (adding == 0) return;
			if (tooMuchLeftOut()) {
				// Too much would be left out: built anew.
				rebuild(source, sourceVersion);
				return;
			}
			lookUpAdded(adding);
			// Draws came or went: what's in view is marked again ({@link #markInView}). The mask itself stays, so
			// only the batches of the draws that came or went are filled again.
			maskedListGeneration = -1;
			if (!MOVING_FILL) filled = false;
		}

		/**
		 * Marks the sections of vanilla's list as those drawn (frames built from the {@link #wideSections}). False if the
		 * list has one this frame wasn't built with, and the caller falls back ({@link #draw}).
		 */
		boolean markInView(ObjectArrayList<SectionRenderDispatcher.RenderSection> visible) {
			if (filtered && maskedListGeneration == listGeneration) return true;
			Arrays.fill(inView, 0, sectionCount, false);
			Object[] list = visible.elements();
			int n = visible.size();
			for (int k = 0; k < n; k++) {
				BatchedSection built = (BatchedSection) list[k];
				if (built.afterburner$frameAt(layer) != stamp) return false;
				int t = built.afterburner$frameIndex(layer);
				if (t >= 0 && t < sectionCount) inView[t] = true;
			}
			filtered = true;
			maskedListGeneration = listGeneration;
			return true;
		}

		/** Every section of the frame is drawn (built from vanilla's list, or a wide frame drawn while it backs off). */
		void drawAll() {
			filtered = false;
		}

		/** Looks the sections {@link #update} is adding up, and adds their draws at the end. */
		private void lookUpAdded(int n) {
			for (int k = 0; k < n; k++) {
				lookUp(addingSections[k]);
				addingSections[k] = null;
			}
			appendedCount += n;
		}

		private int adding(SectionRenderDispatcher.RenderSection section, int n) {
			if (n == addingSections.length) addingSections = Arrays.copyOf(addingSections, n * 2);
			addingSections[n] = section;
			return n + 1;
		}

		/**
		 * Adds a section's draws, from its stored mesh. Returns its index in {@link #sections}, -1 if it has nothing to
		 * draw. Its mesh version and place in the frame are remembered either way.
		 */
		private int lookUp(SectionRenderDispatcher.RenderSection section) {
			BatchedSection built = (BatchedSection) section;
			// Read before the mesh: a change after this leaves the version lower than the mesh's, so the next frame
			// looks the section up again rather than keeping what it saw here.
			int version = built.afterburner$meshVersion(layer);
			SectionMesh mesh = built.afterburner$meshes()[layer];
			if (mesh == null || built.afterburner$isEmpty(layer)) {
				built.afterburner$built(layer, version);
				built.afterburner$inFrame(layer, stamp, -1);
				return -1;
			}
			int index = addSection(section);
			Region region = mesh.region;
			if (region == null) {
				record(null, 0, 0, 0, OWN, index, mesh);
			} else {
				touchRegion(region);
				if (mesh.indexOffset >= 0) record(region.batch(layer, mesh.format, true), mesh.indexOffset, mesh.indexCount, mesh.baseVertex, SORTED, index, mesh);
				else if (mesh.faces != null) record(region.batch(layer, mesh.format, false), 0, mesh.indexCount, mesh.baseVertex, FACES, index, mesh);
				else record(region.batch(layer, mesh.format, false), 0, mesh.indexCount, mesh.baseVertex, PLAIN, index, mesh);
			}
			built.afterburner$built(layer, version);
			built.afterburner$inFrame(layer, stamp, index);
			return index;
		}

		private int addSection(SectionRenderDispatcher.RenderSection section) {
			if (sectionCount == sections.length) {
				int n = sectionCount * 2;
				sections = Arrays.copyOf(sections, n);
				sectionOrigin = Arrays.copyOf(sectionOrigin, n * 3);
				sectionFirstDraw = Arrays.copyOf(sectionFirstDraw, n);
				dead = Arrays.copyOf(dead, n);
				early = Arrays.copyOf(early, n);
				filledEarly = Arrays.copyOf(filledEarly, n);
				inView = Arrays.copyOf(inView, n);
				filledInView = Arrays.copyOf(filledInView, n);
			}
			int i = sectionCount++;
			sections[i] = section;
			BlockPos origin = section.getOrigin();
			sectionOrigin[i * 3] = origin.getX();
			sectionOrigin[i * 3 + 1] = origin.getY();
			sectionOrigin[i * 3 + 2] = origin.getZ();
			sectionFirstDraw[i] = draws;
			dead[i] = false;
			early[i] = true;
			return i;
		}

		private void touchRegion(Region region) {
			if (region.usedAt[layer] != stamp) {
				region.usedAt[layer] = stamp;
				// This layer's draws of the region are made again from scratch: entries of an older frame must not show up.
				region.clearBatches(layer);
				regions.add(region);
			}
		}

		/** Adds a section draw. The batch is null for meshes in the section's own buffer. */
		private void record(@Nullable Batch batch, long indexOffset, int indexCount, int baseVertex, int kind, int section, SectionMesh mesh) {
			if (draws == drawBatch.length) growDraws();
			int i = draws++;
			drawBatch[i] = batch;
			drawMesh[i] = mesh;
			drawOffset[i] = indexOffset;
			drawCount[i] = indexCount;
			drawBase[i] = baseVertex;
			drawKind[i] = (byte) kind;
			drawSection[i] = section;
			drawMoves[i] = false;
			drawAlive[i] = false;
			drawPov[i] = TranslucentCulling.NO_PARTITION;
			drawShown[i] = drawQuads[i] = 0;
			if (batch != null) refill(batch);
			else ownDraws++;
		}

		private void growDraws() {
			int n = draws * 2;
			drawBatch = Arrays.copyOf(drawBatch, n);
			drawMesh = Arrays.copyOf(drawMesh, n);
			drawOffset = Arrays.copyOf(drawOffset, n);
			drawCount = Arrays.copyOf(drawCount, n);
			drawBase = Arrays.copyOf(drawBase, n);
			drawSection = Arrays.copyOf(drawSection, n);
			drawPov = Arrays.copyOf(drawPov, n);
			drawKind = Arrays.copyOf(drawKind, n);
			drawMoves = Arrays.copyOf(drawMoves, n);
			drawAlive = Arrays.copyOf(drawAlive, n);
			drawHolds = Arrays.copyOf(drawHolds, n * HOLDS);
			drawShown = Arrays.copyOf(drawShown, n);
			drawQuads = Arrays.copyOf(drawQuads, n);
		}

		/** Leaves section {@code t}'s draws out of the frame from now on. */
		private void leaveOut(int t) {
			dead[t] = true;
			deadCount++;
			int first = sectionFirstDraw[t], end = t + 1 < sectionCount ? sectionFirstDraw[t + 1] : draws;
			for (int d = first; d < end; d++) {
				drawMesh[d] = null;
				drawAlive[d] = false;
				if (drawBatch[d] != null) refill(drawBatch[d]);
				else ownDraws--;
				// Not counted from now on, also if it's never filled again.
				uncount(d);
			}
		}

		/** Has a batch filled again at the next fill (if the batches were filled since they were last emptied). */
		private void refill(Batch batch) {
			if (!filled || batch.refill) return;
			batch.refill = true;
			refills.add(batch);
		}

		/**
		 * Fills the batches for this camera position, leaving out the faces it can't see. With occlusion culling, the
		 * draws of sections hidden at the last test are left out, and with a wide frame those vanilla's own list doesn't
		 * have ({@link #filtered}). Only the batches with a draw that can add something else than the last fill are
		 * filled again ({@link #holds}).
		 */
		void fill(double cx, double cy, double cz, boolean occlusion) {
			this.occlusion = occlusion;
			int bx = Mth.floor(cx), by = Mth.floor(cy), bz = Mth.floor(cz);
			if (occlusion) sortOut(cx, cy, cz);
			int fill = ++fillStamp;
			boolean moved = cx != filledX || cy != filledY || cz != filledZ;
			boolean partial = filled && (MOVING_FILL || !moved) && occlusion == filledOcclusion && filtered == filledFiltered;
			boolean dirty = !partial;
			if (ownDraws > 0) dirty = true;
			for (int k = 0; k < refills.size(); k++) {
				Batch batch = refills.get(k);
				batch.refill = false;
				if (partial) {
					batch.dirtyAt = fill;
					dirty = true;
				}
			}
			refills.clear();
			if (partial && (occlusion || filtered || moved)) {
				// Each draw adds the same as at its last fill unless its section was hidden or shown anew, was marked in
				// view or out of it, or the camera moved past where it holds: only the batches of those are filled again.
				for (int i = 0; i < draws; i++) {
					Batch batch = drawBatch[i];
					if (batch == null || batch.dirtyAt == fill) continue;
					int t = drawSection[i];
					if (deadCount > 0 && dead[t]) continue;
					if (occlusion && early[t] != filledEarly[t] || filtered && inView[t] != filledInView[t]
							|| moved && drawMoves[i] && !holds(i, cx, cy, cz, bx, by, bz)) {
						batch.dirtyAt = fill;
						dirty = true;
					}
				}
			}
			long faceTotal = SectionFaces.totalQuads, faceDrawn = SectionFaces.drawnQuads;
			long waterTotal = TranslucentCulling.totalQuads, waterDrawn = TranslucentCulling.drawnQuads;
			if (!partial) shownFaces = allFaces = shownWater = allWater = 0;
			if (dirty) {
				for (int i = 0; i < draws; i++) fillOne(i, partial, fill, cx, cy, cz, bx, by, bz);
			}
			// The report counts what the batches hold, also those left as they were.
			SectionFaces.totalQuads = faceTotal + allFaces;
			SectionFaces.drawnQuads = faceDrawn + shownFaces;
			TranslucentCulling.totalQuads = waterTotal + allWater;
			TranslucentCulling.drawnQuads = waterDrawn + shownWater;
			filled = true;
			filledX = cx;
			filledY = cy;
			filledZ = cz;
			filledOcclusion = occlusion;
			filledFiltered = filtered;
			if (occlusion) {
				if (filledEarly.length < sectionCount) filledEarly = Arrays.copyOf(filledEarly, sections.length);
				System.arraycopy(early, 0, filledEarly, 0, sectionCount);
			}
			if (filtered) {
				if (filledInView.length < sectionCount) filledInView = new boolean[inView.length];
				System.arraycopy(inView, 0, filledInView, 0, sectionCount);
			}
		}

		private void fillOne(int i, boolean partial, int fill, double cx, double cy, double cz, int bx, int by, int bz) {
			int t = drawSection[i];
			if (drawKind[i] == OWN) {
				// In the section's own buffer: drawn as vanilla does, whenever it isn't hidden.
				drawAlive[i] = !(deadCount > 0 && dead[t]) && (!filtered || inView[t]) && (!occlusion || early[t]);
				return;
			}
			Batch batch = drawBatch[i];
			if (partial && batch.dirtyAt != fill) return;
			// Emptied even when all its draws are left out.
			if (batch.filledAt != fill) {
				batch.size = 0;
				batch.filledAt = fill;
			}
			if (partial) uncount(i);
			if (deadCount > 0 && dead[t] || filtered && !inView[t] || occlusion && !early[t]) {
				drawMoves[i] = false;
				return;
			}
			fillDraw(i, batch, cx, cy, cz, bx, by, bz);
		}

		/** Adds what the camera sees of one section draw to its batch. */
		private void fillDraw(int i, Batch batch, double cx, double cy, double cz, int bx, int by, int bz) {
			drawMoves[i] = false;
			int t = drawSection[i] * 3;
			switch (drawKind[i]) {
				case FACES -> {
					double x = cx - sectionOrigin[t], y = cy - sectionOrigin[t + 1], z = cz - sectionOrigin[t + 2];
					long drawn = SectionFaces.drawnQuads, total = SectionFaces.totalQuads;
					SectionFaces.addVisible(batch, drawMesh[i].faces, drawBase[i], x, y, z);
					drawShown[i] = (int) (SectionFaces.drawnQuads - drawn);
					drawQuads[i] = (int) (SectionFaces.totalQuads - total);
					shownFaces += drawShown[i];
					allFaces += drawQuads[i];
					drawMoves[i] = MOVING_FILL && SectionFaces.holds(drawMesh[i].faces, x, y, z, drawHolds, i * HOLDS);
				}
				case SORTED -> {
					SectionMesh mesh = drawMesh[i];
					int count = mesh.indexCount;
					if (mesh.pointOfView != TranslucentCulling.NO_PARTITION) {
						int pov = TranslucentCulling.pointOfView(bx, by, bz, sectionOrigin[t], sectionOrigin[t + 1], sectionOrigin[t + 2]);
						if (TranslucentCulling.holds(mesh.pointOfView, pov)) count = mesh.frontIndices;
						drawPov[i] = pov;
						drawMoves[i] = MOVING_FILL;
					}
					TranslucentCulling.totalQuads += mesh.indexCount / 6;
					TranslucentCulling.drawnQuads += count / 6;
					drawShown[i] = count / 6;
					drawQuads[i] = mesh.indexCount / 6;
					shownWater += drawShown[i];
					allWater += drawQuads[i];
					if (count > 0) batch.add(mesh.indexOffset, count, mesh.baseVertex);
				}
				default -> batch.add(drawOffset[i], drawCount[i], drawBase[i]);
			}
		}

		/** Whether draw {@code i} adds the same for the camera here as at its last fill. */
		private boolean holds(int i, double cx, double cy, double cz, int bx, int by, int bz) {
			int t = drawSection[i] * 3;
			if (drawKind[i] == SORTED) {
				SectionMesh mesh = drawMesh[i];
				if (mesh.pointOfView == TranslucentCulling.NO_PARTITION) return true;
				return drawPov[i] == TranslucentCulling.pointOfView(bx, by, bz, sectionOrigin[t], sectionOrigin[t + 1], sectionOrigin[t + 2]);
			}
			double x = cx - sectionOrigin[t], y = cy - sectionOrigin[t + 1], z = cz - sectionOrigin[t + 2];
			// Test where the camera may go with the same groups visible, as the last fill left it (the 26.3 batcher's
			// holds): a cheap range test, no per-group pass. Outside it the batch is filled again below, which is what
			// works the range out anew, so nothing is recomputed here.
			return SectionFaces.holdsAt(drawHolds, i * HOLDS, x, y, z);
		}

		/** Takes what draw {@code i} counted for the report when last filled off the counts. */
		private void uncount(int i) {
			if (drawKind[i] == FACES) {
				shownFaces -= drawShown[i];
				allFaces -= drawQuads[i];
			} else if (drawKind[i] == SORTED) {
				shownWater -= drawShown[i];
				allWater -= drawQuads[i];
			}
		}

		/** Sections around the camera and those not hidden at the last test are drawn. */
		private void sortOut(double cx, double cy, double cz) {
			int leftOut = 0;
			for (int t = 0; t < sectionCount; t++) {
				boolean early = !OcclusionCulling.leftOut(sections[t], cx, cy, cz);
				this.early[t] = early;
				if (!early) leftOut++;
			}
			if (OcclusionCulling.measuring) OcclusionCulling.leftOutSections += leftOut;
		}

		/** Draws what was collected into the touched regions' batches, and the sections in their own buffers. */
		void draw(double camX, double camY, double camZ, Matrix4f modelView, Matrix4f projection) {
			Window window = Minecraft.getInstance().getWindow();
			BufferUploader.invalidate();
			// Draws are read in format order (the format is part of the batch), so all draws of one programme go out
			// together and the program is bound once for them. The program is only applied once a batch of that format
			// is actually reached, so a format with nothing to draw costs neither a bind nor a scan of the regions.
			for (int format = VANILLA; format < FORMATS; format++) {
				ShaderInstance shader = format == VANILLA ? RenderSystem.getShader() : CompactVertices.shader(layer);
				if (shader == null) continue;
				Uniform offset = null;
				boolean applied = false;
				for (int r = 0; r < regions.size(); r++) {
					Region region = regions.get(r);
					if (region.deleted) continue;
					for (int sorted = 0; sorted < 2; sorted++) {
						Batch batch = region.batchOrNull(layer, format, sorted == 1);
						if (batch == null || batch.size == 0) continue;
						if (!applied) {
							drawing = format;
							shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, window);
							shader.apply();
							offset = shader.CHUNK_OFFSET;
							applied = true;
						}
						if (offset != null) {
							offset.set((float) (region.x - camX), (float) (region.y - camY), (float) (region.z - camZ));
							offset.upload();
						}
						GlStateManager._glBindVertexArray(region.array(format, sorted == 1));
						batch.draw();
					}
				}
				if (applied) {
					if (offset != null) offset.set(0.0F, 0.0F, 0.0F);
					shader.clear();
					drawing = -1;
				}
			}
			drawOwn(camX, camY, camZ, modelView, projection, window);
			VertexBuffer.unbind();
		}

		/** Vanilla's draw, for the sections whose mesh is in their own vertex buffer. */
		private void drawOwn(double camX, double camY, double camZ, Matrix4f modelView, Matrix4f projection, Window window) {
			if (ownDraws == 0) return;
			boolean any = false;
			for (int i = 0; i < draws && !any; i++) any = drawKind[i] == OWN && drawAlive[i];
			if (!any) return;
			ShaderInstance shader = RenderSystem.getShader();
			if (shader == null) return;
			shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, window);
			shader.apply();
			Uniform offset = shader.CHUNK_OFFSET;
			for (int i = 0; i < draws; i++) {
				if (drawKind[i] != OWN || !drawAlive[i]) continue;
				SectionRenderDispatcher.RenderSection section = sections[drawSection[i]];
				BlockPos origin = section.getOrigin();
				if (offset != null) {
					offset.set((float) (origin.getX() - camX), (float) (origin.getY() - camY), (float) (origin.getZ() - camZ));
					offset.upload();
				}
				VertexBuffer buffer = section.getBuffer(type);
				buffer.bind();
				buffer.draw();
				drawCalls++;
			}
			if (offset != null) offset.set(0.0F, 0.0F, 0.0F);
			shader.clear();
		}
	}

	/** The draws of one region, format and kind of indices in a frame. Render thread only. */
	static final class Batch implements SectionFaces.Sink {
		int size;
		/** Fill that last emptied this batch, and that found it must be filled again. */
		int filledAt = -1, dirtyAt = -1;
		/** In its frame's refills. */
		boolean refill;
		int[] indexCounts = new int[16], baseVertex = new int[16];
		long[] indexOffsets = new long[16];

		@Override
		public void add(int indexCount, int baseVertex) {
			add(0, indexCount, baseVertex);
		}

		void add(long indexOffset, int indexCount, int base) {
			if (size == indexCounts.length) {
				indexCounts = java.util.Arrays.copyOf(indexCounts, size * 2);
				baseVertex = java.util.Arrays.copyOf(baseVertex, size * 2);
				indexOffsets = java.util.Arrays.copyOf(indexOffsets, size * 2);
			}
			indexCounts[size] = indexCount;
			indexOffsets[size] = indexOffset;
			baseVertex[size] = base;
			size++;
		}

		void draw() {
			if (size == 0) return;
			drawCalls++;
			sectionDraws += size;
			if (size == 1) {
				GL32C.glDrawElementsBaseVertex(GL11C.GL_TRIANGLES, indexCounts[0], GL11C.GL_UNSIGNED_INT, indexOffsets[0], baseVertex[0]);
				return;
			}
			if (counts.capacity() < size) {
				int capacity = Math.max(size, counts.capacity() * 2);
				MemoryUtil.memFree(offsets);
				MemoryUtil.memFree(counts);
				MemoryUtil.memFree(baseVertices);
				offsets = MemoryUtil.memAllocPointer(capacity);
				counts = MemoryUtil.memAllocInt(capacity);
				baseVertices = MemoryUtil.memAllocInt(capacity);
			}
			for (int i = 0; i < size; i++) {
				offsets.put(i, indexOffsets[i]);
				counts.put(i, indexCounts[i]);
				baseVertices.put(i, baseVertex[i]);
			}
			offsets.position(0).limit(size);
			counts.position(0).limit(size);
			baseVertices.position(0).limit(size);
			GL32C.glMultiDrawElementsBaseVertex(GL11C.GL_TRIANGLES, counts, GL11C.GL_UNSIGNED_INT, offsets, baseVertices);
			offsets.clear();
			counts.clear();
			baseVertices.clear();
		}
	}

	/** The buffers of one region, and its vertex arrays. Render thread only. */
	static final class Region {
		final long key;
		/** The region's corner, in blocks. */
		final int x, y, z;
		final Arena vertices = new Arena(FIRST_VERTEX_BYTES);
		private @Nullable Arena indices;
		/** Meshes stored. */
		int meshes;
		/** Vertex arrays per format, for the quad pattern and for sorted indices; and the buffer generations each was made for. */
		private final int[] arrays = new int[FORMATS * 2], madeForVertices = new int[FORMATS * 2], madeForIndices = new int[FORMATS * 2];
		/** Per layer, format, for the quad pattern and for sorted indices. Made when first used. */
		private final Batch[] batches = new Batch[LAYERS.size() * FORMATS * 2];
		/** The build stamp of the frame that last took this region into its draw order, per layer. */
		final int[] usedAt = new int[LAYERS.size()];
		/** Let go of: not drawn any more (a frame may still have it in its draw order). */
		boolean deleted;

		Region(long key, int x, int y, int z) {
			this.key = key;
			this.x = x;
			this.y = y;
			this.z = z;
		}

		private static int batchIndex(int layer, int format, boolean sorted) {
			return (layer * FORMATS + format) * 2 + (sorted ? 1 : 0);
		}

		Batch batch(int layer, int format, boolean sorted) {
			int i = batchIndex(layer, format, sorted);
			Batch batch = batches[i];
			if (batch == null) batches[i] = batch = new Batch();
			return batch;
		}

		@Nullable Batch batchOrNull(int layer, int format, boolean sorted) {
			return batches[batchIndex(layer, format, sorted)];
		}

		/** Empties this layer's batches: entries of a frame before must not be drawn as this one's. */
		void clearBatches(int layer) {
			for (int format = 0; format < FORMATS; format++) {
				for (int sorted = 0; sorted < 2; sorted++) {
					Batch batch = batchOrNull(layer, format, sorted == 1);
					if (batch == null) continue;
					batch.size = 0;
					batch.filledAt = batch.dirtyAt = -1;
					batch.refill = false;
				}
			}
		}

		Arena indices() {
			if (indices == null) indices = new Arena(FIRST_INDEX_BYTES);
			return indices;
		}

		/** The vertex array for a format and kind of indices, made again when a buffer grew (and was replaced) since. */
		int array(int format, boolean sorted) {
			int i = format * 2 + (sorted ? 1 : 0);
			Arena index = sorted ? indices() : null;
			if (arrays[i] == 0 || madeForVertices[i] != vertices.generation || index != null && madeForIndices[i] != index.generation) {
				if (arrays[i] != 0) RenderSystem.glDeleteVertexArrays(arrays[i]);
				arrays[i] = makeArray(format, vertices.buffer, index != null ? index.buffer : quadIndices);
				madeForVertices[i] = vertices.generation;
				madeForIndices[i] = index != null ? index.generation : 0;
			}
			return arrays[i];
		}

		void delete() {
			for (int i = 0; i < arrays.length; i++) {
				if (arrays[i] != 0) RenderSystem.glDeleteVertexArrays(arrays[i]);
				arrays[i] = 0;
			}
			vertices.delete();
			if (indices != null) indices.delete();
			deleted = true;
		}
	}

	/** Makes a vertex array for a region's buffers, left bound. */
	private static int makeArray(int format, int vertexBuffer, int indexBuffer) {
		int array = GlStateManager._glGenVertexArrays();
		GlStateManager._glBindVertexArray(array);
		GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER, vertexBuffer);
		if (format != VANILLA) {
			int stride = stride(format);
			// Locations as the shader's attributes are bound: Position, Color, UV0 (the block format's first three).
			GlStateManager._enableVertexAttribArray(0);
			GlStateManager._vertexAttribIPointer(0, 4, GL11C.GL_UNSIGNED_SHORT, stride, 0);
			GlStateManager._enableVertexAttribArray(1);
			GlStateManager._vertexAttribPointer(1, 4, GL11C.GL_UNSIGNED_BYTE, true, stride, 8);
			GlStateManager._enableVertexAttribArray(2);
			GlStateManager._vertexAttribPointer(2, 2, GL11C.GL_UNSIGNED_SHORT, true, stride, 12);
			if (format == EXTENDED) {
				// A shader pack's: AbMidTex, AbNormal, AbBlock (the game's shaders don't read them).
				GlStateManager._enableVertexAttribArray(3);
				GlStateManager._vertexAttribPointer(3, 2, GL11C.GL_UNSIGNED_SHORT, true, stride, 16);
				GlStateManager._enableVertexAttribArray(4);
				GlStateManager._vertexAttribPointer(4, 4, GL11C.GL_BYTE, true, stride, 20);
				GlStateManager._enableVertexAttribArray(5);
				GlStateManager._vertexAttribIPointer(5, 2, GL11C.GL_UNSIGNED_SHORT, stride, 24);
			}
		} else {
			DefaultVertexFormat.BLOCK.setupBufferState();
		}
		GlStateManager._glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
		return array;
	}

	/**
	 * One GPU buffer, handed out in pieces (first fit, freed pieces joined to their neighbours). When full, it's replaced by
	 * one twice the size with the same contents. Render thread only.
	 */
	static final class Arena {
		int buffer;
		long capacity;
		/** Counts replacements, so vertex arrays made for an older buffer are made again. */
		int generation = 1;
		/** Free pieces: start to length. */
		private final TreeMap<Long, Long> free = new TreeMap<>();

		Arena(long capacity) {
			this.capacity = capacity;
			buffer = make(capacity);
			free.put(0L, capacity);
		}

		private static int make(long bytes) {
			int buffer = GlStateManager._glGenBuffers();
			GlStateManager._glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, buffer);
			GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, bytes, GL15C.GL_STATIC_DRAW);
			return buffer;
		}

		private static long align(long bytes) {
			return (bytes + ALIGN - 1) & -ALIGN;
		}

		long alloc(long bytes) {
			long size = align(bytes);
			while (true) {
				for (Map.Entry<Long, Long> piece : free.entrySet()) {
					long length = piece.getValue();
					if (length < size) continue;
					long at = piece.getKey();
					free.remove(at);
					if (length > size) free.put(at + size, length - size);
					return at;
				}
				grow(size);
			}
		}

		void free(long at, long bytes) {
			long start = at, length = align(bytes);
			Map.Entry<Long, Long> before = free.floorEntry(at);
			if (before != null && before.getKey() + before.getValue() == at) {
				start = before.getKey();
				length += before.getValue();
			}
			Long after = free.remove(at + align(bytes));
			if (after != null) length += after;
			free.put(start, length);
		}

		private void grow(long need) {
			long bigger = Math.max(capacity * 2, align(capacity + need));
			int replacement = make(bigger);
			GlStateManager._glBindBuffer(GL31C.GL_COPY_READ_BUFFER, buffer);
			GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, 0, 0, capacity);
			RenderSystem.glDeleteBuffers(buffer);
			buffer = replacement;
			generation++;
			long old = capacity;
			capacity = bigger;
			free(old, bigger - old);
		}

		long freeBytes() {
			long bytes = 0;
			for (long length : free.values()) bytes += length;
			return bytes;
		}

		void upload(long at, ByteBuffer data) {
			GlStateManager._glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, buffer);
			GL15C.glBufferSubData(GL31C.GL_COPY_WRITE_BUFFER, at, data);
		}

		void delete() {
			if (buffer != 0) RenderSystem.glDeleteBuffers(buffer);
			buffer = 0;
		}
	}
}
