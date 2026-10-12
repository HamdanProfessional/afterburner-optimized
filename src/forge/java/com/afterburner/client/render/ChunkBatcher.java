package com.afterburner.client.render;

import com.afterburner.Afterburner;
import com.afterburner.Features;
import com.afterburner.client.Addons;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the chunk draws of a frame like vanilla's {@code prepareChunkRenders}, but with one draw call per region and
 * layer (a multi-draw of all its sections) instead of one per section and layer. Used when the game draws chunks
 * without indirect multi-draw, which is always the case on Intel graphics.
 * <p>
 * Order is kept where it matters: regions are drawn in the order of their nearest section (near to far, like vanilla),
 * and translucent sections, which must go far to near, are only merged with the section drawn right before them.
 * <p>
 * Batching by region follows Sodium's design (CaffeineMC), built here on vanilla's renderer.
 */
public final class ChunkBatcher {
	private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();
	private static final byte TRANSLUCENT = (byte) ChunkSectionLayer.TRANSLUCENT.ordinal();
	private static final Map<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> NO_DRAWS = new EnumMap<>(ChunkSectionLayer.class);
	/** GPU timer label of each layer (benchmarks). */
	private static final String[] TIMER_LABELS = new String[LAYERS.length];

	static {
		for (ChunkSectionLayer layer : LAYERS) {
			NO_DRAWS.put(layer, List.of());
			TIMER_LABELS[layer.ordinal()] = "terrain layer " + layer.name().toLowerCase();
		}
	}

	/** Draw calls and sections drawn, and frames built anew or patched, for the benchmark report. Render thread only. */
	public static long drawCalls, sectionDraws, builds, patches;
	/** Sections with draws that builds copied from the frame before, and that they looked up anew. */
	public static long copiedSections, lookedUpSections;
	/** Per layer, summed over builds: draws, batches, and position slots with a batch; and draw calls, those of one draw, made. */
	public static final long[] layerDraws = new long[LAYERS.length], layerBatches = new long[LAYERS.length], layerSlots = new long[LAYERS.length],
			layerCalls = new long[LAYERS.length], layerSingles = new long[LAYERS.length];
	/** Summed over builds: sections listed, and those with something to draw. */
	public static long listedSections, drawnSections;
	/** Time waiting for the dispatcher's lock in builds; ordered batches ended by another slot, by other buffers. */
	public static long lockNanos, slotBreaks, bufferBreaks, indexBreaks, otherBreaks;
	/** Time spent building frames anew and filling them, for the benchmark report. */
	public static long buildNanos, buildMaxNanos, fillNanos, fillMaxNanos;

	private static PointerBuffer offsets = MemoryUtil.memAllocPointer(256);
	private static IntBuffer counts = MemoryUtil.memAllocInt(256), baseVertices = MemoryUtil.memAllocInt(256);
	/** Vulkan's form of the same draws: first index, index count, vertex offset per draw. */
	private static IntBuffer interleaved = MemoryUtil.memAllocInt(256 * 3);
	/** How the device takes many draws at once (OpenGL: separate lists, Vulkan: interleaved), found on first use. */
	private static int multiDrawKind = -1, interleavedMax;
	private static final int SEPARATE = 0, INTERLEAVED = 1, ONE_BY_ONE = 2;

	private ChunkBatcher() {
	}

	private static final boolean REUSE = !"false".equals(System.getProperty("afterburner.frameReuse"));
	/** {@code -Dafterburner.fadeInPlace=false} builds the frame anew every frame while a section fades in, as before. */
	private static final boolean FADE_IN_PLACE = !"false".equals(System.getProperty("afterburner.fadeInPlace"));
	/** Why frames were built anew (new meshes, another list of sections, a moved mesh it couldn't patch, fading, anything else), and frames whose fading sections were brought up to date in place. */
	public static long rebuiltForMeshes, rebuiltForList, rebuiltForMoved, rebuiltForFading, rebuiltOther, fadedInPlace;
	/** {@code -Dafterburner.swapInPlace=false} builds the frame anew whenever a section gets a new mesh, as before. */
	private static final boolean SWAP_IN_PLACE = !"false".equals(System.getProperty("afterburner.swapInPlace"));
	/** For testing: with {@code -Dafterburner.checkSwap=true}, every frame changed in place ({@link Frame#update}) is compared with one built anew. */
	private static final boolean CHECK_SWAP = Boolean.getBoolean("afterburner.checkSwap");
	/**
	 * {@code -Dafterburner.movingFill=false} fills every batch again whenever the camera moved or the frame changed, as before,
	 * instead of only the batches of draws that add something else now.
	 */
	private static final boolean MOVING_FILL = !"false".equals(System.getProperty("afterburner.movingFill"));
	/** For testing: with {@code -Dafterburner.checkFill=true}, every fill that left batches as they were is compared with a whole one. */
	private static final boolean CHECK_FILL = Boolean.getBoolean("afterburner.checkFill");
	/** Fills, those that left batches as they were, draws filled and draws there were; in check mode, fills compared and those that differed. */
	public static long fills, partialFills, drawsFilled, drawsSeen, fillChecks, fillDifferent;
	/**
	 * Frames that took sections' new meshes in place, and those sections; frames that took a new list of sections in place;
	 * changes to sections outside the frame's list (left alone); frames built anew because too much would have been left out;
	 * in check mode, frames compared and those that differed; and updates in place, and the time they took.
	 */
	public static long swappedFrames, swappedSections, listsTaken, ignoredChanges, rebuiltForLeftOut, swapChecks, swapDifferent, updateCalls, updateNanos;
	/** Sections taken in again because their moved meshes couldn't be patched ({@link Frame#update}). */
	public static long retakenSections;
	/** Counts lists taken in place, so a section knows whether it's in the new one ({@link BuiltSection#afterburner$listedAt}). */
	private static int listStamps;
	/** Bumped whenever a section's mesh or a mesh's place in the chunk buffers changes; any thread. */
	private static volatile int generation;
	/** Bumped when something changes that the frame can't take in section by section; any thread. */
	private static volatile int otherGeneration;
	/** Sections that got a new mesh (or none) since the last frame, which takes them in in place ({@link Frame#update}); any thread. */
	private static final ConcurrentLinkedQueue<SectionRenderDispatcher.RenderSection> CHANGED = new ConcurrentLinkedQueue<>();
	private static final AtomicInteger changedCount = new AtomicInteger();
	private static final int MAX_CHANGED = 4096;
	/** The changed sections this frame takes in. */
	private static SectionRenderDispatcher.RenderSection[] changedNow = new SectionRenderDispatcher.RenderSection[64];
	/** The last frame built, reused while the same sections are visible and nothing above changed. */
	private static @Nullable Frame last;
	/** The frame built before it, emptied: the next build fills it, saving the garbage. */
	private static @Nullable Frame spareFrame;
	/** Counts frame builds, so a section knows which build took it in ({@link BuiltSection}). */
	private static int buildStamps;
	private static @Nullable SectionRenderDispatcher lastDispatcher;
	private static Object[] lastSections = new Object[0];
	private static int lastSectionCount, lastGeneration, lastOtherGeneration;
	/** Vanilla's sections, which the frame's drawn ones were last marked for (when it's built from the {@link WideSections}). */
	private static Object[] lastVisible = new Object[0];
	private static int lastVisibleCount;
	private static boolean occlusionWasOn = true;

	/** Meshes that moved in the chunk buffers since the last frame (mostly water sorted anew), patched into it in place. */
	private static final ReferenceOpenHashSet<Object> moved = new ReferenceOpenHashSet<>();
	/** A shader pack's shadow map draws, once one has been asked for. */
	private static @Nullable ShadowFrame shadow;

	/** Something changed that isn't one section's mesh: the next frame is built anew. */
	public static void changed() {
		generation++;
		otherGeneration++;
	}

	/** The section got a new mesh, or none; any thread. */
	public static void changed(SectionRenderDispatcher.RenderSection section) {
		generation++;
		if (changedCount.incrementAndGet() > MAX_CHANGED) {
			changedCount.decrementAndGet();
			otherGeneration++;
			return;
		}
		CHANGED.add(section);
	}

	/** Takes the changed sections into {@link #changedNow}; returns how many. */
	private static int takeChanged() {
		int n = 0;
		SectionRenderDispatcher.RenderSection section;
		while ((section = CHANGED.poll()) != null) {
			changedCount.decrementAndGet();
			if (n == changedNow.length) changedNow = Arrays.copyOf(changedNow, n * 2);
			changedNow[n++] = section;
		}
		return n;
	}

	/** Changes whenever a section gets a new mesh (or none). */
	public static int generation() {
		return generation;
	}

	/** A mesh got a new place in the chunk buffers; any thread. */
	public static void moved(Object mesh) {
		if (RenderSystem.isOnRenderThread() && moved.size() < 4096) {
			moved.add(mesh);
		} else if (mesh instanceof RegionMesh regional && regional.afterburner$owner() instanceof SectionRenderDispatcher.RenderSection owner) {
			// Its draws in the frame are its section's: taken in like the section's new mesh.
			changed(owner);
		} else {
			changed();
		}
	}

	public static ChunkSectionsToRender prepare(List<SectionRenderDispatcher.RenderSection> visibleSections, @Nullable SectionRenderDispatcher dispatcher,
			long fadeDuration, long now, CameraRenderState cameraState, GpuTextureView blockAtlas, Matrix4fc modelView, boolean respectTranslucentOrder) {
		Vec3 camera = cameraState.pos;
		if (dispatcher != lastDispatcher) WideSections.invalidate();
		// Built from the sections in a frustum wider than the camera's, of which those in vanilla's list are drawn: turning a little
		// changes which are drawn, not what the frame holds, so it isn't built anew for that.
		List<SectionRenderDispatcher.RenderSection> wide = dispatcher != null && !cameraState.isFrustumCaptured ? WideSections.get(cameraState) : null;
		Frame frame = frameFor(wide != null ? wide : visibleSections, dispatcher, fadeDuration, now, respectTranslucentOrder);
		if (wide == null) {
			frame.drawAll();
		} else if (!frame.marked || !sameVisible(visibleSections)) {
			if (!frame.markInView(visibleSections)) {
				// Vanilla's list has a section the wide one doesn't (turned past its margin): picked anew for the camera now.
				frame = frameFor(WideSections.pick(cameraState), dispatcher, fadeDuration, now, respectTranslucentOrder);
				if (!frame.markInView(visibleSections)) {
					WideSections.backOff();
					frame = frameFor(visibleSections, dispatcher, fadeDuration, now, respectTranslucentOrder);
					frame.drawAll();
				}
			}
			rememberVisible(visibleSections);
		}
		long f0 = System.nanoTime();
		// The occlusion test reads the game's depth, which a shader pack draws elsewhere.
		boolean occlusion = OcclusionCulling.ENABLED && Features.OCCLUSION_CULLING.active() && !Addons.shaderPackActive();
		// Switched back on in the settings: the answers from before it was off are too old to trust.
		if (occlusion && !occlusionWasOn) OcclusionCulling.skipAhead();
		occlusionWasOn = occlusion;
		Profiler.get().push("afterburnerFill");
		frame.fill(camera, occlusion && dispatcher != null && OcclusionCulling.begin(modelView, camera));
		Profiler.get().pop();
		long f = System.nanoTime() - f0;
		fillNanos += f;
		fillMaxNanos = Math.max(fillMaxNanos, f);
		GpuBufferSlice terrainTransform = RenderSystem.getDynamicUniforms().writeTerrainTransform(modelView, blockAtlas.getWidth(0), blockAtlas.getHeight(0));
		frame.infoSlices = RenderSystem.getDynamicUniforms().writeChunkSections(frame.infoArray);
		if (frame.largestIndexCount != 0) RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).requestIndexCount(frame.largestIndexCount);
		ChunkSectionsToRender result = new ChunkSectionsToRender.DrawSeparate(terrainTransform, NO_DRAWS, frame.largestIndexCount, frame.infoSlices);
		((BatchedSections) result).afterburner$setFrame(frame);
		return result;
	}

	/** The frame for these sections: the last one if they and their meshes are the same, patched for meshes that moved, or one built anew. */
	private static Frame frameFor(List<SectionRenderDispatcher.RenderSection> visibleSections, @Nullable SectionRenderDispatcher dispatcher,
			long fadeDuration, long now, boolean respectTranslucentOrder) {
		int gen = generation, otherGen = otherGeneration;
		int changedSections = takeChanged();
		Frame frame = last;
		boolean other = !REUSE || frame == null || frame.respectTranslucentOrder != respectTranslucentOrder || dispatcher != lastDispatcher;
		boolean meshes = !other && (SWAP_IN_PLACE ? otherGen != lastOtherGeneration : gen != lastGeneration);
		boolean fading = !other && !meshes && frame.fading && !FADE_IN_PLACE;
		boolean rebuild = other || meshes || fading;
		boolean list = false, leftOut = false, unpatched = false, updated = false;
		if (!rebuild) {
			boolean sameList = sameSections(visibleSections);
			if (SWAP_IN_PLACE && dispatcher != null && (!sameList || changedSections > 0 || !moved.isEmpty())) {
				// Taken in place: sections that left the list or got a new mesh are left out, those that joined it or got one are
				// looked up and added at the end, moved meshes are patched (or their sections taken in again), and water and glass
				// are put in the list's order again.
				long u0 = System.nanoTime();
				int done = frame.update(visibleSections, !sameList, changedNow, changedSections, dispatcher, fadeDuration, now, moved);
				updateNanos += System.nanoTime() - u0;
				updateCalls++;
				leftOut = done == Frame.TOO_MUCH_LEFT_OUT;
				rebuild = done < 0;
				if (!rebuild) {
					// Checked whatever it took in: a list, new meshes, moved ones.
					updated = true;
					if (frame.swappedNow > 0) swappedFrames++;
					if (!sameList) {
						listsTaken++;
						rememberSections(visibleSections);
					}
				}
			} else if (!sameList) {
				rebuild = list = true;
			} else if (!moved.isEmpty()) {
				rebuild = unpatched = dispatcher == null || !frame.patch(moved, dispatcher);
				if (!rebuild) patches++;
			}
		}
		Arrays.fill(changedNow, 0, changedSections, null);
		// Sections fading in only change how see-through they are: their slots are brought up to date, the rest stays.
		if (!rebuild && frame.fading) {
			frame.fade(now, fadeDuration);
			fadedInPlace++;
		}
		if (CHECK_SWAP && updated) check(frame, visibleSections, dispatcher, fadeDuration, now);
		if (shadow != null) shadow.take(moved);
		moved.clear();
		if (rebuild) {
			builds++;
			if (other) rebuiltOther++;
			else if (meshes) rebuiltForMeshes++;
			else if (fading) rebuiltForFading++;
			else if (list) rebuiltForList++;
			else if (leftOut) rebuiltForLeftOut++;
			else if (unpatched) rebuiltForMoved++;
			// Built from the frame before (which drew last frame): the sections whose meshes stayed the same take its draws.
			Frame old = REUSE && dispatcher == lastDispatcher ? frame : null;
			Frame next = spareFrame != null ? spareFrame.reset(respectTranslucentOrder) : new Frame(respectTranslucentOrder, true);
			spareFrame = null;
			long t0 = System.nanoTime();
			Profiler.get().push("afterburnerBuild");
			if (dispatcher != null) next.build(visibleSections, dispatcher, fadeDuration, now, old);
			Profiler.get().pop();
			long t = System.nanoTime() - t0;
			// Done with now: its arrays and batches make the next build.
			if (frame != null) spareFrame = frame.reset(respectTranslucentOrder);
			frame = next;
			buildNanos += t;
			listedSections += visibleSections.size();
			drawnSections += frame.sectionCount;
			for (int l = 0; l < LAYERS.length; l++) {
				layerBatches[l] += frame.batches[l].size();
				layerSlots[l] += frame.byInfo[l].size();
			}
			for (int i = 0; i < frame.draws; i++) layerDraws[frame.drawLayer[i]]++;
			buildMaxNanos = Math.max(buildMaxNanos, t);
			last = frame;
			lastDispatcher = dispatcher;
			lastGeneration = gen;
			lastOtherGeneration = otherGen;
			rememberSections(visibleSections);
		}
		return frame;
	}

	/** Check mode: compares a frame changed in place with one built anew from the same sections. */
	private static void check(Frame frame, List<SectionRenderDispatcher.RenderSection> visibleSections, SectionRenderDispatcher dispatcher,
			long fadeDuration, long now) {
		long looked = lookedUpSections, lock = lockNanos;
		Frame fresh = new Frame(frame.respectTranslucentOrder, false);
		fresh.build(visibleSections, dispatcher, fadeDuration, now, null);
		lookedUpSections = looked;
		lockNanos = lock;
		Map<String, Integer> wanted = new HashMap<>();
		for (int i = 0; i < fresh.draws; i++) wanted.merge(fresh.describe(i), 1, Integer::sum);
		int extra = 0;
		String example = null;
		for (int i = 0; i < frame.draws; i++) {
			if (frame.dead[frame.drawSection[i]]) continue;
			String draw = frame.describe(i);
			Integer n = wanted.get(draw);
			if (n == null) {
				extra++;
				if (example == null) example = draw;
			} else if (n == 1) {
				wanted.remove(draw);
			} else {
				wanted.put(draw, n - 1);
			}
		}
		boolean inOrder = true;
		if (frame.respectTranslucentOrder) {
			List<String> have = new ArrayList<>(), want = new ArrayList<>();
			for (int k = 0; k < frame.orderCount; k++) {
				int i = frame.order[k];
				if (!frame.dead[frame.drawSection[i]]) have.add(frame.describe(i));
			}
			for (int k = 0; k < fresh.orderCount; k++) want.add(fresh.describe(fresh.order[k]));
			inOrder = have.equals(want);
		}
		swapChecks++;
		if (extra == 0 && wanted.isEmpty() && inOrder) return;
		if (++swapDifferent <= 20) {
			Afterburner.LOGGER.error("Swap check failed: {} draws that a frame built anew doesn't have (e.g. {}), {} missing (e.g. {}), water and glass {}",
					extra, example, wanted.size(), wanted.isEmpty() ? null : wanted.keySet().iterator().next(), inOrder ? "in order" : "out of order");
		}
	}

	private static int[] vertexSizes() {
		int[] vertexSize = new int[LAYERS.length];
		for (ChunkSectionLayer layer : LAYERS) vertexSize[layer.ordinal()] = layer.pipeline(false).getVertexFormatBinding(0).getVertexSize();
		return vertexSize;
	}

	private static Object[] elements(List<SectionRenderDispatcher.RenderSection> sections) {
		return sections instanceof ObjectArrayList<SectionRenderDispatcher.RenderSection> list ? list.elements() : sections.toArray();
	}

	private static boolean sameSections(List<SectionRenderDispatcher.RenderSection> sections) {
		int n = sections.size();
		return n == lastSectionCount && Arrays.equals(elements(sections), 0, n, lastSections, 0, n);
	}

	private static void rememberSections(List<SectionRenderDispatcher.RenderSection> sections) {
		int n = sections.size();
		if (lastSections.length < n) lastSections = new Object[Math.max(n, lastSections.length * 2)];
		System.arraycopy(elements(sections), 0, lastSections, 0, n);
		if (lastSectionCount > n) Arrays.fill(lastSections, n, lastSectionCount, null);
		lastSectionCount = n;
	}

	/** Whether vanilla's list is the one the drawn sections were last marked for. */
	private static boolean sameVisible(List<SectionRenderDispatcher.RenderSection> sections) {
		int n = sections.size();
		return n == lastVisibleCount && Arrays.equals(elements(sections), 0, n, lastVisible, 0, n);
	}

	private static void rememberVisible(List<SectionRenderDispatcher.RenderSection> sections) {
		int n = sections.size();
		if (lastVisible.length < n) lastVisible = new Object[Math.max(n, lastVisible.length * 2)];
		System.arraycopy(elements(sections), 0, lastVisible, 0, n);
		if (lastVisibleCount > n) Arrays.fill(lastVisible, n, lastVisibleCount, null);
		lastVisibleCount = n;
	}

	/** Everything one frame draws, per layer. */
	public static final class Frame {
		/** Per section draw: face groups ({@link SectionFaces#SIZE} ints) and the corner they're relative to, if it has them. */
		private static final int FACES = SectionFaces.SIZE + 3;
		/** x, y, z, x + z and x - z, each low and high ({@link #drawHolds}). */
		private static final int HOLDS = 10;
		/** What {@link #lookUp} returns for a section whose draws mustn't be copied into the next build. */
		private static final int INCOMPLETE = -2;
		private boolean respectTranslucentOrder;
		/** Whether the sections remember this frame's builds ({@link BuiltSection}): the main frames do, the shadow map's doesn't. */
		private final boolean tracked;
		/** Which build this is ({@link #buildStamps}), if tracked. */
		private int stamp;
		/** While the next frame is built from this one: the slot each of its position slots got there, and that build's stamp. */
		private int[] infoForward = new int[64], infoForwardAt = new int[64];
		final List<DynamicGpuData.ChunkSectionInfo> infos = new ArrayList<>();
		DynamicGpuData.ChunkSectionInfo[] infoArray = new DynamicGpuData.ChunkSectionInfo[0];
		GpuBufferSlice @Nullable [] infoSlices;
		/** A section is still fading in, so its visibility changes every frame ({@link #fade}). */
		boolean fading;
		/** Per section of a build: where what was read for it under the dispatcher's lock is kept (-1: its draws are copied), and its mesh version. */
		private int[] lookAt = new int[64], versions = new int[64];
		/** Per section looked up: its mesh, and its buffer slice for each layer it has. */
		private @Nullable SectionMesh[] lookedMeshes = new SectionMesh[64];
		private SectionRenderDispatcher.@Nullable RenderSectionBufferSlice[] lookedSlices = new SectionRenderDispatcher.RenderSectionBufferSlice[64 * LAYERS.length];
		/** The sections still fading in, each with a slot of its own, and those slots. */
		private SectionRenderDispatcher.RenderSection[] fadingSections = new SectionRenderDispatcher.RenderSection[16];
		private int[] fadingSlots = new int[16];
		private int fadingCount;
		/** Sections whose draws are left out: they left the list or got a new mesh, whose draws were added at the end ({@link #update}). */
		private boolean[] dead = new boolean[64];
		private int deadCount;
		/** Sections added since the build ({@link #update}), and the sections {@link #update} is adding. */
		private int appendedCount;
		private SectionRenderDispatcher.RenderSection[] addingSections = new SectionRenderDispatcher.RenderSection[64];
		/** Changed sections the last {@link #update} looked up anew. */
		int swappedNow;
		/** Water and glass draws in the list's order, when that order is kept: filled in that order ({@link #fill}). */
		private int[] order = new int[256];
		private int orderCount;
		/** The water and glass batches being replaced by {@link #reorder}. */
		private final List<Batch> reordered = new ArrayList<>();
		int largestIndexCount;
		/** Every section draw, in order, kept in plain arrays so following frames can go through them quickly. */
		private int draws;
		private Batch[] drawBatch = new Batch[256];
		private long[] drawOffset = new long[256];
		private int[] drawCount = new int[256], drawBase = new int[256];
		private boolean[] drawFaced = new boolean[256];
		/** Mesh and layer of each draw, to patch it when the mesh moves. */
		private Object[] drawMesh = new Object[256];
		private byte[] drawLayer = new byte[256];
		/** Meshes of visible sections left out for a layer not uploaded yet: the frame is built anew once they are. */
		private final ReferenceOpenHashSet<Object> pending = new ReferenceOpenHashSet<>();
		private int[] drawFaces = new int[256 * FACES];
		/** Section of each draw, and (this frame) where its commands start in its batch's held-back draws, and how many. */
		private int[] drawSection = new int[256], drawLateStart = new int[256], drawLateCount = new int[256];
		/** Translucent draws: indices facing the camera from the point of view their buffer was sorted at ({@link TranslucentCulling}). */
		private int[] drawFront = new int[256], drawView = new int[256];
		/**
		 * Per draw, from its last fill: where the camera may go with it adding the same ({@link #HOLDS} open ranges, relative to
		 * its corner), and whether that's not everywhere; and for the report, which quads it counts in (0 none, 1 face groups,
		 * 2 water and glass), how many it drew and how many it has.
		 */
		private float[] drawHolds = new float[256 * HOLDS];
		private boolean[] drawMoves = new boolean[256];
		private byte[] drawKind = new byte[256];
		private int[] drawShown = new int[256], drawQuads = new int[256];
		/** Sections with something to draw, in drawing order, with their block origin (x, y, z). */
		SectionRenderDispatcher.RenderSection[] sections = new SectionRenderDispatcher.RenderSection[64];
		private int[] sectionOrigin = new int[64 * 3];
		/** Each section's first draw (its draws come one after another), and whether its mesh is in region coordinates. */
		private int[] sectionFirstDraw = new int[64];
		/** Each section's water and glass draw, or -1. */
		private int[] sectionTranslucent = new int[64];
		private boolean[] sectionInRegion = new boolean[64];
		/** The animated textures each section shows, or null ({@link AnimatedSprites}). */
		private SpriteContents @Nullable [] @Nullable [] sectionAnimated = new SpriteContents[64][];
		int sectionCount;
		/** This frame: whether {@link OcclusionCulling} runs, and which sections are drawn before its test. */
		private boolean occlusion;
		private boolean[] early = new boolean[64];
		private int lateCommands;
		/** Whether only the sections marked in view are drawn ({@link #markInView}), and whether they're marked for this build. */
		private boolean filtered;
		boolean marked;
		private boolean[] inView = new boolean[64], filledInView = new boolean[64];
		private SpriteContents @Nullable [] @Nullable [] viewAnimated = new SpriteContents[64][];
		/** What the batches were last filled for, to skip filling them again for the same. */
		private boolean filled, filledOcclusion, filledFiltered;
		/** Filled with every draw whole ({@link #fillAll}) since the last build or patch. */
		private boolean allFilled;
		private double filledX, filledY, filledZ;
		private boolean[] filledEarly = new boolean[64];
		/** The report's counts for the batches as filled: face-group quads drawn and all of them, water and glass the same. */
		private long shownFaces, allFaces, shownWater, allWater;
		/** Batches to fill again at the next fill, which leaves the others as they are ({@link #refill}). */
		private final List<Batch> refills = new ArrayList<>();
		private int fillStamp;
		/** Position slot of each region whose sections are all fully faded in. */
		private final Long2IntOpenHashMap regionInfo = new Long2IntOpenHashMap();
		@SuppressWarnings("unchecked")
		private final List<Batch>[] batches = new List[LAYERS.length];
		/** Per layer: batch of each position slot (others with different buffers hang off it as {@code next}). */
		@SuppressWarnings("unchecked")
		private final Int2ObjectOpenHashMap<Batch>[] byInfo = new Int2ObjectOpenHashMap[LAYERS.length];
		/** Batches of an earlier build, to be filled again. */
		private final List<Batch> spare = new ArrayList<>();

		Frame(boolean respectTranslucentOrder, boolean tracked) {
			this.respectTranslucentOrder = respectTranslucentOrder;
			this.tracked = tracked;
			regionInfo.defaultReturnValue(-1);
			for (int i = 0; i < LAYERS.length; i++) {
				batches[i] = new ArrayList<>();
				byInfo[i] = new Int2ObjectOpenHashMap<>();
			}
		}

		/** Empties the frame for another build. */
		Frame reset(boolean respectTranslucentOrder) {
			this.respectTranslucentOrder = respectTranslucentOrder;
			infos.clear();
			infoSlices = null;
			fading = false;
			Arrays.fill(fadingSections, 0, fadingCount, null);
			fadingCount = 0;
			deadCount = 0;
			appendedCount = 0;
			orderCount = 0;
			for (Batch batch : refills) batch.refill = false;
			refills.clear();
			largestIndexCount = 0;
			Arrays.fill(drawBatch, 0, draws, null);
			Arrays.fill(drawMesh, 0, draws, null);
			pending.clear();
			draws = 0;
			Arrays.fill(sections, 0, sectionCount, null);
			Arrays.fill(sectionAnimated, 0, sectionCount, null);
			sectionCount = 0;
			occlusion = false;
			lateCommands = 0;
			filled = false;
			allFilled = false;
			filtered = marked = false;
			regionInfo.clear();
			for (int i = 0; i < LAYERS.length; i++) {
				spare.addAll(batches[i]);
				batches[i].clear();
				byInfo[i].clear();
			}
			return this;
		}

		/**
		 * Builds the frame's draws for these sections. Looking a section's draws up costs a dozen cache misses (mesh, layers,
		 * buffer slices, face groups), so with the frame built before ({@code old}) a section whose mesh stayed the same since
		 * copies its draws from there: when the camera turned or a few sections changed, that's nearly all of them.
		 */
		void build(List<SectionRenderDispatcher.RenderSection> visibleSections, SectionRenderDispatcher dispatcher, long fadeDuration, long now,
				@Nullable Frame old) {
			int[] vertexSize = new int[LAYERS.length];
			for (ChunkSectionLayer layer : LAYERS) vertexSize[layer.ordinal()] = layer.pipeline(false).getVertexFormatBinding(0).getVertexSize();
			if (tracked) stamp = ++buildStamps;
			int oldStamp = old != null && old.tracked ? old.stamp : 0;
			if (oldStamp != 0 && old.infoForward.length < old.infos.size()) {
				old.infoForward = new int[old.infos.size() * 2];
				old.infoForwardAt = new int[old.infos.size() * 2];
			}
			int n = visibleSections.size();
			Object[] list = elements(visibleSections);
			if (lookAt.length < n) {
				lookAt = new int[n * 2];
				versions = new int[n * 2];
			}
			// Which sections take their draws from the frame before, and which are looked up.
			int looked = 0;
			for (int k = 0; k < n; k++) {
				if (tracked) {
					BuiltSection built = (BuiltSection) list[k];
					// Read before the mesh: a change after this makes the next build look it up again.
					int version = built.afterburner$meshVersion();
					versions[k] = version;
					if (oldStamp != 0 && built.afterburner$builtAt() == oldStamp && built.afterburner$builtVersion() == version) {
						lookAt[k] = -1;
						continue;
					}
				}
				lookAt[k] = looked++;
			}
			if (looked > 0) readLocked(list, n, looked, dispatcher);
			for (int k = 0; k < n; k++) {
				SectionRenderDispatcher.RenderSection section = (SectionRenderDispatcher.RenderSection) list[k];
				int j = lookAt[k];
				if (!tracked) {
					lookUp(section, j, vertexSize, fadeDuration, now);
					continue;
				}
				BuiltSection built = (BuiltSection) section;
				int version = versions[k];
				if (j < 0) {
					int t = built.afterburner$builtIndex();
					int index = t < 0 ? -1 : copy(old, t, section);
					built.afterburner$built(stamp, index, version);
					built.afterburner$inFrame(stamp, index);
					continue;
				}
				int before = sectionCount;
				int index = lookUp(section, j, vertexSize, fadeDuration, now);
				built.afterburner$built(index == INCOMPLETE ? 0 : stamp, index, version);
				// Its place even when its draws can't be copied: a section is added once, at the end.
				built.afterburner$inFrame(stamp, sectionCount > before ? before : -1);
			}
			Arrays.fill(lookedMeshes, 0, looked, null);
			Arrays.fill(lookedSlices, 0, looked * LAYERS.length, null);
			// Built in the list's order, so water and glass are in it.
			orderCount = 0;
			if (respectTranslucentOrder) {
				if (order.length < draws) order = new int[Math.max(draws, order.length * 2)];
				for (int i = 0; i < draws; i++) if (drawLayer[i] == TRANSLUCENT) order[orderCount++] = i;
			}
			infoArray = infos.toArray(new DynamicGpuData.ChunkSectionInfo[0]);
			if (tracked && AnimatedSprites.ENABLED) AnimatedSprites.setVisible(sectionAnimated, sectionCount);
		}

		/**
		 * Reads the meshes and buffer slices of the sections to be looked up ({@link #lookAt}), all under one hold of the
		 * dispatcher's lock: the builder threads wait on it while it's held, and each time it's let go one of them is woken.
		 */
		private void readLocked(Object[] list, int n, int looked, SectionRenderDispatcher dispatcher) {
			if (lookedMeshes.length < looked) lookedMeshes = new SectionMesh[looked * 2];
			if (lookedSlices.length < looked * LAYERS.length) lookedSlices = new SectionRenderDispatcher.RenderSectionBufferSlice[looked * 2 * LAYERS.length];
			long l0 = System.nanoTime();
			dispatcher.lock();
			lockNanos += System.nanoTime() - l0;
			try {
				for (int k = 0; k < n; k++) {
					int j = lookAt[k];
					if (j < 0) continue;
					SectionMesh sectionMesh = ((SectionRenderDispatcher.RenderSection) list[k]).getSectionMesh();
					lookedMeshes[j] = sectionMesh;
					if (!(sectionMesh instanceof CompiledSectionMesh mesh)) continue;
					RegionMesh regional = (RegionMesh) mesh;
					for (ChunkSectionLayer layer : LAYERS) {
						lookedSlices[j * LAYERS.length + layer.ordinal()] = mesh.getSectionDraw(layer) == null ? null : regional.afterburner$slice(dispatcher, layer);
					}
				}
			} finally {
				dispatcher.unlock();
			}
		}

		/**
		 * Adds a section's draws, looked up from its mesh. Returns its index in {@link #sections}, -1 if it has nothing to draw,
		 * or {@link #INCOMPLETE} if what it has can't be copied into the next build: still fading in, or a layer isn't uploaded yet.
		 */
		private int lookUp(SectionRenderDispatcher.RenderSection section, int j, int[] vertexSize, long fadeDuration, long now) {
			SectionMesh sectionMesh = lookedMeshes[j];
			if (!(sectionMesh instanceof CompiledSectionMesh mesh)) return -1;
			RegionMesh regional = (RegionMesh) mesh;
			boolean inRegion = regional.afterburner$inRegion();
			BlockPos origin = section.getRenderOrigin();
			int info = -1, index = -1;
			boolean complete = true;
			for (ChunkSectionLayer layer : LAYERS) {
				SectionMesh.SectionDraw draw = mesh.getSectionDraw(layer);
				if (draw == null) continue;
				SectionRenderDispatcher.RenderSectionBufferSlice slice = lookedSlices[j * LAYERS.length + layer.ordinal()];
				if (slice == null || draw.hasCustomIndexBuffer() && slice.indexBuffer() == null) {
					pending.add(mesh);
					complete = false;
					continue;
				}
				if (info < 0) {
					float visibility = section.getVisibility(now, fadeDuration);
					fading |= visibility < 1.0F;
					complete &= visibility >= 1.0F;
					info = info(origin.getX(), origin.getY(), origin.getZ(), inRegion, visibility);
					if (visibility < 1.0F) addFading(section, info);
					index = addSection(section, origin.getX(), origin.getY(), origin.getZ(), inRegion,
							AnimatedSprites.ENABLED ? ((AnimatedMesh) mesh).afterburner$animated() : null);
				}
				int format = regional.afterburner$format(layer);
				int baseVertex = (int) (slice.vertexBufferOffset() / CompactVertices.vertexSize(format, vertexSize[layer.ordinal()]));
				GpuBuffer indexBuffer = null;
				IndexType indexType = null;
				long indexOffset = 0;
				if (draw.hasCustomIndexBuffer()) {
					indexBuffer = slice.indexBuffer();
					indexType = draw.indexType();
					indexOffset = slice.indexBufferOffset() / indexType.bytes * indexType.bytes;
				} else {
					largestIndexCount = Math.max(largestIndexCount, draw.indexCount());
				}
				Batch batch = batch(layer, info, slice.vertexBuffer(), indexBuffer, indexType, format);
				SectionFaces faces = mesh instanceof FaceSortedMesh sorted ? sorted.afterburner$faces(layer) : null;
				boolean faced = faces != null && faces.matches(draw.indexCount());
				int at = record(batch, indexOffset, draw.indexCount(), baseVertex, faced, index);
				drawMesh[draws - 1] = mesh;
				drawLayer[draws - 1] = (byte) layer.ordinal();
				if (layer == ChunkSectionLayer.TRANSLUCENT) sectionTranslucent[index] = draws - 1;
				partition(draws - 1, mesh, layer, draw);
				if (faced) {
					faces.copyTo(drawFaces, at);
					drawFaces[at + SectionFaces.SIZE] = inRegion ? ChunkRegions.originX(origin.getX()) : origin.getX();
					drawFaces[at + SectionFaces.SIZE + 1] = inRegion ? ChunkRegions.originY(origin.getY()) : origin.getY();
					drawFaces[at + SectionFaces.SIZE + 2] = inRegion ? ChunkRegions.originZ(origin.getZ()) : origin.getZ();
				}
			}
			if (index >= 0) lookedUpSections++;
			return complete ? index : INCOMPLETE;
		}

		/** Adds a section's draws as the frame built before has them (as its section {@code t}); returns its index here. */
		private int copy(Frame old, int t, SectionRenderDispatcher.RenderSection section) {
			int x = old.sectionOrigin[t * 3], y = old.sectionOrigin[t * 3 + 1], z = old.sectionOrigin[t * 3 + 2];
			boolean inRegion = old.sectionInRegion[t];
			int first = old.sectionFirstDraw[t], end = t + 1 < old.sectionCount ? old.sectionFirstDraw[t + 1] : old.draws;
			// Its position slot there: the first section of it copied gives it one here, the others take that.
			int oldInfo = old.drawBatch[first].info, info;
			if (old.infoForwardAt[oldInfo] == stamp) {
				info = old.infoForward[oldInfo];
			} else {
				// Only sections fully faded in are copied, so the slot's position and visibility hold as they are.
				info = inRegion ? regionSlot(x, y, z, old.infos.get(oldInfo)) : add(old.infos.get(oldInfo));
				old.infoForwardAt[oldInfo] = stamp;
				old.infoForward[oldInfo] = info;
			}
			int index = addSection(section, x, y, z, inRegion, old.sectionAnimated[t]);
			for (int d = first; d < end; d++) {
				Batch from = old.drawBatch[d];
				Batch batch;
				if (from.forwardAt == stamp) {
					batch = from.forward;
				} else {
					batch = batch(from.layer, info, from.vertexBuffer, from.indexBuffer, from.indexType, from.format);
					// Every draw of a batch there has the same slot and buffers, so it goes in the same batch here. Not so for
					// water and glass in order: a batch there takes the next draw only while it's the last one.
					if (!ordered(from.layer)) {
						from.forward = batch;
						from.forwardAt = stamp;
					}
				}
				boolean faced = old.drawFaced[d];
				int at = record(batch, old.drawOffset[d], old.drawCount[d], old.drawBase[d], faced, index);
				int i = draws - 1;
				drawMesh[i] = old.drawMesh[d];
				drawLayer[i] = old.drawLayer[d];
				if (drawLayer[i] == TRANSLUCENT) sectionTranslucent[index] = i;
				drawFront[i] = old.drawFront[d];
				drawView[i] = old.drawView[d];
				if (faced) System.arraycopy(old.drawFaces, d * FACES, drawFaces, at, FACES);
				if (from.indexType == null) largestIndexCount = Math.max(largestIndexCount, old.drawCount[d]);
			}
			copiedSections++;
			return index;
		}

		/** The part of a translucent draw facing the camera ({@link TranslucentCulling}), if its index buffer has one. */
		private void partition(int i, CompiledSectionMesh mesh, ChunkSectionLayer layer, SectionMesh.SectionDraw draw) {
			drawView[i] = TranslucentCulling.NO_PARTITION;
			if (layer.translucent() && TranslucentCulling.ENABLED && mesh instanceof TranslucentCulling.Partitioned split
					&& split.afterburner$frontIndices() <= draw.indexCount()) {
				drawFront[i] = split.afterburner$frontIndices();
				drawView[i] = split.afterburner$pointOfView();
			}
		}

		/**
		 * Takes in the new places of meshes that moved in the chunk buffers (sorted anew, mostly), when they're still in
		 * the same buffers. False if the frame must be built anew.
		 */
		boolean patch(ReferenceOpenHashSet<Object> moved, SectionRenderDispatcher dispatcher) {
			for (Object mesh : moved) {
				if (pending.contains(mesh)) return false;
			}
			dispatcher.lock();
			try {
				int i = 0;
				while (i < draws) {
					Object m = drawMesh[i];
					if (!moved.contains(m)) {
						i++;
						continue;
					}
					CompiledSectionMesh mesh = (CompiledSectionMesh) m;
					RegionMesh regional = (RegionMesh) m;
					int layers = 0;
					// A section's draws are next to each other.
					for (; i < draws && drawMesh[i] == m; i++) {
						ChunkSectionLayer layer = LAYERS[drawLayer[i]];
						if (!patch(i, mesh, regional, layer, dispatcher)) return false;
						layers |= 1 << layer.ordinal();
					}
					for (ChunkSectionLayer layer : LAYERS) {
						if ((layers >> layer.ordinal() & 1) != 0) continue;
						SectionMesh.SectionDraw draw = mesh.getSectionDraw(layer);
						if (draw == null) continue;
						SectionRenderDispatcher.RenderSectionBufferSlice slice = regional.afterburner$slice(dispatcher, layer);
						if (slice != null && !(draw.hasCustomIndexBuffer() && slice.indexBuffer() == null)) return false;
					}
				}
			} finally {
				dispatcher.unlock();
			}
			if (!MOVING_FILL) filled = false;
			allFilled = false;
			return true;
		}

		private boolean patch(int i, CompiledSectionMesh mesh, RegionMesh regional, ChunkSectionLayer layer, SectionRenderDispatcher dispatcher) {
			SectionMesh.SectionDraw draw = mesh.getSectionDraw(layer);
			if (draw == null) return false;
			SectionRenderDispatcher.RenderSectionBufferSlice slice = regional.afterburner$slice(dispatcher, layer);
			if (slice == null || draw.hasCustomIndexBuffer() && slice.indexBuffer() == null) return false;
			int format = regional.afterburner$format(layer);
			GpuBuffer indexBuffer = draw.hasCustomIndexBuffer() ? slice.indexBuffer() : null;
			IndexType indexType = draw.hasCustomIndexBuffer() ? draw.indexType() : null;
			Batch batch = drawBatch[i];
			if (batch.vertexBuffer != slice.vertexBuffer() || batch.indexBuffer != indexBuffer || batch.indexType != indexType || batch.format != format) {
				return false;
			}
			SectionFaces faces = mesh instanceof FaceSortedMesh sorted ? sorted.afterburner$faces(layer) : null;
			boolean faced = faces != null && faces.matches(draw.indexCount());
			if (faced != drawFaced[i]) return false;
			if (faced) faces.copyTo(drawFaces, i * FACES);
			long indexOffset = 0;
			if (indexType != null) {
				indexOffset = slice.indexBufferOffset() / indexType.bytes * indexType.bytes;
			} else {
				largestIndexCount = Math.max(largestIndexCount, draw.indexCount());
			}
			int vertexSize = CompactVertices.vertexSize(format, layer.pipeline(false).getVertexFormatBinding(0).getVertexSize());
			drawOffset[i] = indexOffset;
			drawCount[i] = draw.indexCount();
			drawBase[i] = (int) (slice.vertexBufferOffset() / vertexSize);
			partition(i, mesh, layer, draw);
			refill(batch);
			return true;
		}

		/** Adds a section draw; returns where its face groups go. */
		private int record(Batch batch, long indexOffset, int indexCount, int baseVertex, boolean faced, int section) {
			if (draws == drawBatch.length) {
				int n = draws * 2;
				drawBatch = Arrays.copyOf(drawBatch, n);
				drawOffset = Arrays.copyOf(drawOffset, n);
				drawCount = Arrays.copyOf(drawCount, n);
				drawBase = Arrays.copyOf(drawBase, n);
				drawFaced = Arrays.copyOf(drawFaced, n);
				drawFaces = Arrays.copyOf(drawFaces, n * FACES);
				drawSection = Arrays.copyOf(drawSection, n);
				drawLateStart = Arrays.copyOf(drawLateStart, n);
				drawLateCount = Arrays.copyOf(drawLateCount, n);
				drawFront = Arrays.copyOf(drawFront, n);
				drawView = Arrays.copyOf(drawView, n);
				drawMesh = Arrays.copyOf(drawMesh, n);
				drawLayer = Arrays.copyOf(drawLayer, n);
				drawHolds = Arrays.copyOf(drawHolds, n * HOLDS);
				drawMoves = Arrays.copyOf(drawMoves, n);
				drawKind = Arrays.copyOf(drawKind, n);
				drawShown = Arrays.copyOf(drawShown, n);
				drawQuads = Arrays.copyOf(drawQuads, n);
			}
			int i = draws++;
			drawBatch[i] = batch;
			drawMoves[i] = false;
			drawKind[i] = 0;
			drawShown[i] = drawQuads[i] = 0;
			refill(batch);
			drawOffset[i] = indexOffset;
			drawCount[i] = indexCount;
			drawBase[i] = baseVertex;
			drawFaced[i] = faced;
			drawSection[i] = section;
			return i * FACES;
		}

		/** What {@link #update} returns when the frame is to be built anew: too much would be left out. */
		static final int TOO_MUCH_LEFT_OUT = -2;
		/** A section's place while {@link #update} is adding it ({@link BuiltSection#afterburner$frameIndex}). */
		private static final int SWAPPING = -3;

		/**
		 * Takes in a new list of sections, sections that got a new mesh and meshes that moved, in place: the draws of sections
		 * that left the list or got a new mesh are left out, sections that joined it or got one are looked up and added at the
		 * end, meshes that moved are patched (or their sections taken in again), and water and glass are put in the list's
		 * order again. Returns how many sections were looked up, or {@link #TOO_MUCH_LEFT_OUT} when the frame is to be built
		 * anew (it may be left half done then). Changes to sections outside the list don't matter here.
		 */
		int update(List<SectionRenderDispatcher.RenderSection> visibleSections, boolean newList, SectionRenderDispatcher.RenderSection[] changed,
				int count, SectionRenderDispatcher dispatcher, long fadeDuration, long now, ReferenceOpenHashSet<Object> moved) {
			swappedNow = 0;
			if (!tracked) return TOO_MUCH_LEFT_OUT;
			int adding = 0, swapped = 0;
			if (newList) {
				int listed = ++listStamps;
				Object[] list = elements(visibleSections);
				for (int k = 0, n = visibleSections.size(); k < n; k++) {
					BuiltSection built = (BuiltSection) list[k];
					built.afterburner$listed(listed);
					if (built.afterburner$frameAt() == stamp) continue;
					// Joined the list.
					built.afterburner$inFrame(stamp, SWAPPING);
					adding = adding((SectionRenderDispatcher.RenderSection) list[k], adding);
				}
				// Left the list: what it had here is left out, and isn't copied into the next build.
				for (int k = 0; k < lastSectionCount; k++) {
					BuiltSection built = (BuiltSection) lastSections[k];
					if (built.afterburner$listedAt() == listed) continue;
					int t = built.afterburner$frameIndex();
					if (t >= 0) leaveOut(t);
					built.afterburner$inFrame(0, -1);
					built.afterburner$built(0, -1, 0);
				}
			}
			for (int k = 0; k < count; k++) {
				SectionRenderDispatcher.RenderSection section = changed[k];
				BuiltSection built = (BuiltSection) section;
				if (built.afterburner$frameAt() != stamp) {
					ignoredChanges++;
					continue;
				}
				int t = built.afterburner$frameIndex();
				// Taken already (a section is often in twice, or it joined the list just now), or its draws here are up to date.
				if (t == SWAPPING || built.afterburner$builtAt() == stamp && built.afterburner$builtVersion() == built.afterburner$meshVersion()) continue;
				built.afterburner$inFrame(stamp, SWAPPING);
				if (t >= 0) {
					leaveOut(t);
					built.afterburner$built(0, -1, 0);
				}
				adding = adding(section, adding);
				swapped++;
			}
			// Patched now that the draws of meshes freed (mostly) are left out.
			if (!moved.isEmpty()) {
				adding = patchOrRetake(moved, dispatcher, adding);
				patches++;
			}
			if (deadCount + appendedCount + adding > Math.max(256, sectionCount / 4)) return TOO_MUCH_LEFT_OUT;
			if (!newList && adding == 0) return 0;
			int from = sectionCount;
			if (adding > 0) lookUpAdded(adding, dispatcher, fadeDuration, now);
			appendedCount += adding;
			swappedSections += swapped;
			boolean water = newList;
			for (int t = from; t < sectionCount && !water; t++) water = sectionTranslucent[t] >= 0;
			if (respectTranslucentOrder && water) reorder(visibleSections);
			infoArray = infos.toArray(new DynamicGpuData.ChunkSectionInfo[0]);
			if (AnimatedSprites.ENABLED) AnimatedSprites.setVisible(sectionAnimated, sectionCount);
			allFilled = marked = false;
			if (!MOVING_FILL) filled = false;
			swappedNow = swapped;
			return adding;
		}

		/**
		 * Patches the draws of meshes that moved in the chunk buffers, as {@link #patch} does; the sections of those it can't
		 * patch (moved to another buffer, a layer uploaded since) are left out and added to those {@link #update} looks up.
		 */
		private int patchOrRetake(ReferenceOpenHashSet<Object> moved, SectionRenderDispatcher dispatcher, int adding) {
			for (Object m : moved) {
				// Not all its layers were there when it was looked up.
				if (pending.remove(m) && m instanceof RegionMesh regional
						&& regional.afterburner$owner() instanceof SectionRenderDispatcher.RenderSection owner) {
					adding = retake(owner, adding);
				}
			}
			dispatcher.lock();
			try {
				int i = 0;
				while (i < draws) {
					Object m = drawMesh[i];
					if (m == null || !moved.contains(m)) {
						i++;
						continue;
					}
					// A section's draws are next to each other, all of its mesh.
					int t = drawSection[i];
					int end = t + 1 < sectionCount ? sectionFirstDraw[t + 1] : draws;
					CompiledSectionMesh mesh = (CompiledSectionMesh) m;
					RegionMesh regional = (RegionMesh) m;
					boolean patched = true;
					int layers = 0;
					for (int d = i; d < end && patched; d++) {
						ChunkSectionLayer layer = LAYERS[drawLayer[d]];
						patched = patch(d, mesh, regional, layer, dispatcher);
						layers |= 1 << layer.ordinal();
					}
					for (int l = 0; l < LAYERS.length && patched; l++) {
						if ((layers >> l & 1) != 0) continue;
						SectionMesh.SectionDraw draw = mesh.getSectionDraw(LAYERS[l]);
						if (draw == null) continue;
						SectionRenderDispatcher.RenderSectionBufferSlice slice = regional.afterburner$slice(dispatcher, LAYERS[l]);
						// A layer it has that the frame doesn't, uploaded since.
						patched = slice == null || draw.hasCustomIndexBuffer() && slice.indexBuffer() == null;
					}
					if (!patched) adding = retake(sections[t], adding);
					i = end;
				}
			} finally {
				dispatcher.unlock();
			}
			if (!MOVING_FILL) filled = false;
			allFilled = false;
			return adding;
		}

		/** Leaves out what a section in the frame has, and adds it to those {@link #update} looks up. */
		private int retake(SectionRenderDispatcher.RenderSection section, int adding) {
			BuiltSection built = (BuiltSection) section;
			if (built.afterburner$frameAt() != stamp) return adding;
			int t = built.afterburner$frameIndex();
			if (t == SWAPPING) return adding;
			built.afterburner$inFrame(stamp, SWAPPING);
			if (t >= 0) leaveOut(t);
			built.afterburner$built(0, -1, 0);
			retakenSections++;
			return adding(section, adding);
		}

		private int adding(SectionRenderDispatcher.RenderSection section, int n) {
			if (n == addingSections.length) addingSections = Arrays.copyOf(addingSections, n * 2);
			addingSections[n] = section;
			return n + 1;
		}

		/** Looks up the sections {@link #update} is adding, and adds their draws at the end. */
		private void lookUpAdded(int n, SectionRenderDispatcher dispatcher, long fadeDuration, long now) {
			if (lookAt.length < n) {
				lookAt = new int[n * 2];
				versions = new int[n * 2];
			}
			for (int k = 0; k < n; k++) {
				lookAt[k] = k;
				// Read before the mesh: a change after this makes it taken in again.
				versions[k] = ((BuiltSection) addingSections[k]).afterburner$meshVersion();
			}
			readLocked(addingSections, n, n, dispatcher);
			int[] vertexSize = vertexSizes();
			for (int k = 0; k < n; k++) {
				SectionRenderDispatcher.RenderSection section = addingSections[k];
				BuiltSection built = (BuiltSection) section;
				int before = sectionCount;
				int index = lookUp(section, k, vertexSize, fadeDuration, now);
				built.afterburner$built(index == INCOMPLETE ? 0 : stamp, index, versions[k]);
				built.afterburner$inFrame(stamp, sectionCount > before ? before : -1);
			}
			Arrays.fill(lookedMeshes, 0, n, null);
			Arrays.fill(lookedSlices, 0, n * LAYERS.length, null);
			Arrays.fill(addingSections, 0, n, null);
		}

		/** Leaves section {@code t}'s draws out of the frame from now on. */
		private void leaveOut(int t) {
			dead[t] = true;
			deadCount++;
			sectionAnimated[t] = null;
			int first = sectionFirstDraw[t], end = t + 1 < sectionCount ? sectionFirstDraw[t + 1] : draws;
			// Not patched when its mesh moves (it's freed, mostly).
			for (int d = first; d < end; d++) {
				drawMesh[d] = null;
				refill(drawBatch[d]);
				// Not counted from now on, also if it's never filled again (water and glass, put in order without it).
				uncount(d);
			}
		}

		/**
		 * Puts the water and glass draws in the list's order again ({@link #order}), each run of draws with the same slot and
		 * buffers one batch, as a build does.
		 */
		private void reorder(List<SectionRenderDispatcher.RenderSection> visibleSections) {
			List<Batch> ordered = batches[TRANSLUCENT];
			// Kept out of the spare batches until done: their slots and buffers are read for the new ones.
			reordered.addAll(ordered);
			ordered.clear();
			if (order.length < draws) order = new int[Math.max(draws, order.length * 2)];
			int m = 0;
			Object[] list = elements(visibleSections);
			for (int k = 0, n = visibleSections.size(); k < n; k++) {
				int t = ((BuiltSection) list[k]).afterburner$frameIndex();
				if (t < 0) continue;
				int d = sectionTranslucent[t];
				if (d < 0) continue;
				Batch from = drawBatch[d];
				Batch batch = ordered.isEmpty() ? null : ordered.getLast();
				if (batch == null || !batch.matches(from.info, from.vertexBuffer, from.indexBuffer, from.indexType, from.format)) {
					batch = newBatch(from.layer, from.info, from.vertexBuffer, from.indexBuffer, from.indexType, from.format);
					ordered.add(batch);
					refill(batch);
				}
				drawBatch[d] = batch;
				order[m++] = d;
			}
			orderCount = m;
			spare.addAll(reordered);
			reordered.clear();
		}

		/** Check mode: what draw {@code i} draws, from where, at which position. */
		String describe(int i) {
			Batch batch = drawBatch[i];
			int t = drawSection[i] * 3;
			int faces = drawFaced[i] ? Arrays.hashCode(Arrays.copyOfRange(drawFaces, i * FACES, i * FACES + FACES)) : 0;
			int front = drawView[i] != TranslucentCulling.NO_PARTITION ? drawFront[i] : -1;
			return System.identityHashCode(drawMesh[i]) + " " + drawLayer[i] + " " + System.identityHashCode(batch.vertexBuffer) + " "
					+ System.identityHashCode(batch.indexBuffer) + " " + batch.indexType + " " + batch.format + " " + drawOffset[i] + " " + drawCount[i] + " "
					+ drawBase[i] + " " + faces + " " + drawView[i] + " " + front + " " + infos.get(batch.info) + " " + sectionOrigin[t] + ","
					+ sectionOrigin[t + 1] + "," + sectionOrigin[t + 2];
		}

		private void addFading(SectionRenderDispatcher.RenderSection section, int slot) {
			if (fadingCount == fadingSlots.length) {
				fadingSlots = Arrays.copyOf(fadingSlots, fadingCount * 2);
				fadingSections = Arrays.copyOf(fadingSections, fadingCount * 2);
			}
			fadingSections[fadingCount] = section;
			fadingSlots[fadingCount++] = slot;
		}

		/**
		 * Gives each section still fading in its visibility for now, the same as a build now would. A section fading in has a
		 * slot of its own, so only its slot changes; once it's fully visible it keeps that slot (one more draw call) until the
		 * next build puts it with its region.
		 */
		void fade(long now, long fadeDuration) {
			boolean still = false;
			for (int k = 0; k < fadingCount; k++) {
				int slot = fadingSlots[k];
				DynamicGpuData.ChunkSectionInfo info = infoArray[slot];
				float visibility = fadingSections[k].getVisibility(now, fadeDuration);
				if (visibility != info.visibility()) {
					info = new DynamicGpuData.ChunkSectionInfo(info.x(), info.y(), info.z(), visibility);
					infoArray[slot] = info;
					infos.set(slot, info);
				}
				still |= visibility < 1.0F;
			}
			fading = still;
		}

		private int addSection(SectionRenderDispatcher.RenderSection section, int x, int y, int z, boolean inRegion, SpriteContents @Nullable [] animated) {
			if (sectionCount == sections.length) {
				sections = Arrays.copyOf(sections, sectionCount * 2);
				sectionOrigin = Arrays.copyOf(sectionOrigin, sectionCount * 6);
				sectionFirstDraw = Arrays.copyOf(sectionFirstDraw, sectionCount * 2);
				sectionInRegion = Arrays.copyOf(sectionInRegion, sectionCount * 2);
				sectionAnimated = Arrays.copyOf(sectionAnimated, sectionCount * 2);
				dead = Arrays.copyOf(dead, sectionCount * 2);
				sectionTranslucent = Arrays.copyOf(sectionTranslucent, sectionCount * 2);
			}
			int i = sectionCount++;
			sections[i] = section;
			sectionOrigin[i * 3] = x;
			sectionOrigin[i * 3 + 1] = y;
			sectionOrigin[i * 3 + 2] = z;
			sectionFirstDraw[i] = draws;
			sectionInRegion[i] = inRegion;
			sectionAnimated[i] = animated;
			dead[i] = false;
			sectionTranslucent[i] = -1;
			return i;
		}

		/**
		 * Marks the sections of vanilla's list as those drawn (a frame built from the {@link WideSections}); false if the list has
		 * one this frame wasn't built with. The animated textures shown are those of the marked sections.
		 */
		boolean markInView(List<SectionRenderDispatcher.RenderSection> visible) {
			marked = false;
			if (inView.length < sections.length) inView = new boolean[sections.length];
			Arrays.fill(inView, 0, sectionCount, false);
			Object[] list = elements(visible);
			for (int k = 0, n = visible.size(); k < n; k++) {
				BuiltSection built = (BuiltSection) list[k];
				if (built.afterburner$frameAt() != stamp) return false;
				int t = built.afterburner$frameIndex();
				if (t >= 0) inView[t] = true;
			}
			filtered = marked = true;
			if (AnimatedSprites.ENABLED) {
				if (viewAnimated.length < sectionCount) viewAnimated = new SpriteContents[sections.length][];
				int m = 0;
				for (int t = 0; t < sectionCount; t++) if (inView[t]) viewAnimated[m++] = sectionAnimated[t];
				// Kept until the next mark: they're read at the next texture update.
				AnimatedSprites.setVisible(viewAnimated, m);
			}
			return true;
		}

		/** Every section of the frame is drawn (built from vanilla's list). */
		void drawAll() {
			if (filtered && AnimatedSprites.ENABLED) AnimatedSprites.setVisible(sectionAnimated, sectionCount);
			filtered = marked = false;
		}

		/**
		 * Fills the batches for this camera position, leaving out the faces it can't see. With occlusion culling, the
		 * draws of sections hidden at the last test are left out, or (same-frame mode, solid and cutout only) held back
		 * for after the test.
		 */
		void fill(Vec3 camera, boolean occlusion) {
			boolean partial = fillOnce(camera, occlusion);
			if (CHECK_FILL && partial) checkFill(camera, occlusion);
		}

		/** Fills the batches; true if it left some as they were. */
		private boolean fillOnce(Vec3 camera, boolean occlusion) {
			double cx = camera.x, cy = camera.y, cz = camera.z;
			int bx = (int) Math.floor(cx), by = (int) Math.floor(cy), bz = (int) Math.floor(cz);
			this.occlusion = occlusion;
			if (occlusion) sortOut(cx, cy, cz);
			int stamp = ++fillStamp;
			boolean moved = cx != filledX || cy != filledY || cz != filledZ;
			// Each draw adds the same as at its last fill unless its section was hidden or shown anew (occlusion test, view), the
			// camera moved past where it holds, or it changed (taken in, left out, patched): only the batches of those are
			// filled again.
			boolean partial = filled && (MOVING_FILL || !moved) && occlusion == filledOcclusion && filtered == filledFiltered;
			for (Batch batch : refills) {
				batch.refill = false;
				if (partial) batch.dirtyAt = stamp;
			}
			refills.clear();
			if (partial && (occlusion || filtered || moved)) {
				// Sections added since the last fill have their batches filled again; their entries here don't matter.
				if (filledEarly.length < sectionCount) filledEarly = Arrays.copyOf(filledEarly, sections.length);
				if (filledInView.length < sectionCount) filledInView = Arrays.copyOf(filledInView, sections.length);
				for (int i = 0; i < draws; i++) {
					Batch batch = drawBatch[i];
					if (batch.dirtyAt == stamp) continue;
					int t = drawSection[i];
					if (deadCount > 0 && dead[t]) continue;
					if (occlusion && early[t] != filledEarly[t] || filtered && inView[t] != filledInView[t] || moved && drawMoves[i] && !holds(i, cx, cy, cz)) {
						batch.dirtyAt = stamp;
					}
				}
			}
			long faceTotal = SectionFaces.totalQuads, faceDrawn = SectionFaces.drawnQuads;
			long translucentTotal = TranslucentCulling.totalQuads, translucentDrawn = TranslucentCulling.drawnQuads;
			if (!partial) shownFaces = allFaces = shownWater = allWater = 0;
			// Water and glass in order go in the list's order ({@link #order}), which their draws needn't be in.
			boolean inOrder = respectTranslucentOrder;
			for (int i = 0; i < draws; i++) {
				if (inOrder && drawLayer[i] == TRANSLUCENT) continue;
				fillOne(i, partial, stamp, cx, cy, cz, bx, by, bz);
			}
			if (inOrder) for (int k = 0; k < orderCount; k++) fillOne(order[k], partial, stamp, cx, cy, cz, bx, by, bz);
			// The report counts what the batches hold, also those left as they were.
			SectionFaces.totalQuads = faceTotal + allFaces;
			SectionFaces.drawnQuads = faceDrawn + shownFaces;
			TranslucentCulling.totalQuads = translucentTotal + allWater;
			TranslucentCulling.drawnQuads = translucentDrawn + shownWater;
			fills++;
			if (partial) partialFills++;
			drawsSeen += draws;
			filled = true;
			filledX = cx;
			filledY = cy;
			filledZ = cz;
			filledOcclusion = occlusion;
			filledFiltered = filtered;
			if (filtered) {
				if (filledInView.length < sectionCount) filledInView = new boolean[inView.length];
				System.arraycopy(inView, 0, filledInView, 0, sectionCount);
			}
			if (occlusion) {
				if (filledEarly.length < sectionCount) filledEarly = new boolean[early.length];
				System.arraycopy(early, 0, filledEarly, 0, sectionCount);
				writeOcclusion(cx, cy, cz);
			}
			return partial;
		}

		/** Whether draw {@code i} adds the same for the camera here as at its last fill (see {@link #fillDraw}). */
		private boolean holds(int i, double cx, double cy, double cz) {
			double x, y, z;
			if (drawFaced[i]) {
				int corner = i * FACES + SectionFaces.SIZE;
				x = cx - drawFaces[corner];
				y = cy - drawFaces[corner + 1];
				z = cz - drawFaces[corner + 2];
			} else {
				int t = drawSection[i] * 3;
				x = cx - sectionOrigin[t];
				y = cy - sectionOrigin[t + 1];
				z = cz - sectionOrigin[t + 2];
			}
			float[] box = drawHolds;
			int h = i * HOLDS;
			double u = x + z, v = x - z;
			return x > box[h] && x < box[h + 1] && y > box[h + 2] && y < box[h + 3] && z > box[h + 4] && z < box[h + 5] && u > box[h + 6]
					&& u < box[h + 7] && v > box[h + 8] && v < box[h + 9];
		}

		/** Has a batch filled again at the next fill (if the batches were filled since they were last emptied). */
		private void refill(Batch batch) {
			if (!filled || batch.refill) return;
			batch.refill = true;
			refills.add(batch);
		}

		private void fillOne(int i, boolean partial, int fill, double cx, double cy, double cz, int bx, int by, int bz) {
			Batch batch = drawBatch[i];
			if (partial && batch.dirtyAt != fill) return;
			// Emptied even when all its draws are left out.
			if (batch.filledAt != fill) {
				batch.size = batch.lateSize = 0;
				batch.filledAt = fill;
			}
			if (partial) uncount(i);
			int t = drawSection[i];
			if (filtered && !inView[t] || deadCount > 0 && dead[t]) {
				drawLateStart[i] = batch.lateSize;
				drawLateCount[i] = 0;
				drawMoves[i] = false;
				drawKind[i] = 0;
				return;
			}
			drawsFilled++;
			fillDraw(i, batch, cx, cy, cz, bx, by, bz);
			if (drawKind[i] == 1) {
				shownFaces += drawShown[i];
				allFaces += drawQuads[i];
			} else if (drawKind[i] == 2) {
				shownWater += drawShown[i];
				allWater += drawQuads[i];
			}
		}

		/** Takes what draw {@code i} counted for the report when last filled off the counts. */
		private void uncount(int i) {
			if (drawKind[i] == 1) {
				shownFaces -= drawShown[i];
				allFaces -= drawQuads[i];
			} else if (drawKind[i] == 2) {
				shownWater -= drawShown[i];
				allWater -= drawQuads[i];
			}
			drawKind[i] = 0;
		}

		/** Check mode: fills the batches whole and compares them with what the fill before left in them. */
		private void checkFill(Vec3 camera, boolean occlusion) {
			long[] partial = fillContents();
			long faces = shownFaces, water = shownWater;
			long filledBefore = drawsFilled, seenBefore = drawsSeen, fillsBefore = fills;
			filled = false;
			fillOnce(camera, occlusion);
			drawsFilled = filledBefore;
			drawsSeen = seenBefore;
			fills = fillsBefore;
			long[] whole = fillContents();
			fillChecks++;
			int differ = -1;
			for (int k = 0; k < Math.max(partial.length, whole.length) && differ < 0; k++) {
				if (k >= partial.length || k >= whole.length || partial[k] != whole[k]) differ = k;
			}
			if (differ < 0 && faces == shownFaces && water == shownWater) return;
			if (++fillDifferent <= 20) {
				Afterburner.LOGGER.error("Fill check failed: batch {} of {} differs, face quads drawn {} / {}, water and glass {} / {}", differ, whole.length,
						faces, shownFaces, water, shownWater);
			}
		}

		/** Check mode: what each batch holds (in order, every layer), then what the occlusion test is told of each draw. */
		private long[] fillContents() {
			int n = 1;
			for (List<Batch> list : batches) n += list.size();
			long[] out = new long[n];
			int k = 0;
			for (List<Batch> list : batches) {
				for (Batch batch : list) out[k++] = batch.contents();
			}
			long h = 0;
			for (int i = 0; i < draws; i++) {
				if (drawLateCount[i] == 0) continue;
				h = mix(h, i);
				h = mix(h, (long) drawLateStart[i] << 32 | drawLateCount[i]);
			}
			out[k] = h;
			return out;
		}

		/**
		 * Sums up the draws: each one's mesh, buffers, place in them and section ({@link ShadowFrame#contents}). Water and glass
		 * without their indices, which sorting them anew for the camera moves but which show the same.
		 */
		long shadowContents() {
			long h = draws;
			for (int i = 0; i < draws; i++) {
				Batch batch = drawBatch[i];
				int t = drawSection[i] * 3;
				h = mix(h, System.identityHashCode(drawMesh[i]));
				h = mix(h, drawLayer[i] | batch.format << 8);
				h = mix(h, System.identityHashCode(batch.vertexBuffer));
				if (!batch.translucent) {
					h = mix(h, System.identityHashCode(batch.indexBuffer));
					h = mix(h, drawOffset[i]);
				}
				h = mix(h, (long) drawCount[i] << 32 | drawBase[i] & 0xFFFFFFFFL);
				h = mix(h, (long) sectionOrigin[t] << 40 ^ (long) sectionOrigin[t + 1] << 20 ^ sectionOrigin[t + 2]);
			}
			return h;
		}

		private static long mix(long h, long v) {
			h = (h ^ v) * 0x9E3779B97F4A7C15L;
			return h ^ h >>> 29;
		}

		/** Fills the batches with every draw whole, for a view other than the camera's (the sun's). */
		void fillAll() {
			if (allFilled) return;
			int stamp = ++fillStamp;
			for (int i = 0; i < draws; i++) {
				Batch batch = drawBatch[i];
				if (batch.filledAt != stamp) {
					batch.size = batch.lateSize = 0;
					batch.filledAt = stamp;
				}
				batch.add(drawOffset[i], drawCount[i], drawBase[i]);
			}
			occlusion = false;
			allFilled = true;
		}

		/** Whether the layer has anything to draw as the batches are filled. */
		boolean drawsAny(ChunkSectionLayer layer) {
			for (Batch batch : batches[layer.ordinal()]) {
				if (batch.size > 0) return true;
			}
			return false;
		}

		/** Adds what the camera sees of one section draw to its batch. */
		private void fillDraw(int i, Batch batch, double cx, double cy, double cz, int bx, int by, int bz) {
			boolean hidden = occlusion && !early[drawSection[i]];
			boolean late = hidden && !batch.translucent;
			int before = batch.lateSize;
			drawLateStart[i] = before;
			drawLateCount[i] = 0;
			drawMoves[i] = false;
			drawKind[i] = 0;
			// Left out while hidden at the last test. Water and glass too, unless every section waits for this frame's
			// test: they're drawn far to near, so they can't be held back for it.
			if (hidden && !OcclusionCulling.SAME_FRAME) return;
			SectionFaces.Sink sink = late ? batch.late : batch;
			if (drawFaced[i]) {
				int at = i * FACES, corner = at + SectionFaces.SIZE;
				double x = cx - drawFaces[corner], y = cy - drawFaces[corner + 1], z = cz - drawFaces[corner + 2];
				long total = SectionFaces.totalQuads, drawn = SectionFaces.drawnQuads;
				SectionFaces.addVisible(sink, drawFaces, at, drawBase[i], x, y, z);
				drawKind[i] = 1;
				drawShown[i] = (int) (SectionFaces.drawnQuads - drawn);
				drawQuads[i] = (int) (SectionFaces.totalQuads - total);
				drawMoves[i] = MOVING_FILL && SectionFaces.holds(drawFaces, at, x, y, z, drawHolds, i * HOLDS);
			} else if (drawView[i] != TranslucentCulling.NO_PARTITION) {
				int t = drawSection[i] * 3, count = drawCount[i];
				int ox = sectionOrigin[t], oy = sectionOrigin[t + 1], oz = sectionOrigin[t + 2];
				if (TranslucentCulling.holds(drawView[i], TranslucentCulling.pointOfView(bx, by, bz, ox, oy, oz))) count = drawFront[i];
				TranslucentCulling.totalQuads += drawCount[i] / 6;
				TranslucentCulling.drawnQuads += count / 6;
				drawKind[i] = 2;
				drawShown[i] = count / 6;
				drawQuads[i] = drawCount[i] / 6;
				if (count > 0) sink.add(drawOffset[i], count, drawBase[i]);
				if (MOVING_FILL) {
					// The point of view only changes when the camera passes one of the section's sides. From the block it's in, as
					// the point of view is: cx - ox can round onto a side the camera isn't past.
					int h = i * HOLDS;
					sides(h, bx - ox);
					sides(h + 2, by - oy);
					sides(h + 4, bz - oz);
					drawHolds[h + 6] = drawHolds[h + 8] = Float.NEGATIVE_INFINITY;
					drawHolds[h + 7] = drawHolds[h + 9] = Float.POSITIVE_INFINITY;
					drawMoves[i] = true;
				}
			} else {
				sink.add(drawOffset[i], drawCount[i], drawBase[i]);
			}
			drawLateCount[i] = batch.lateSize - before;
		}

		/**
		 * Where a camera in block {@code relative} to a section's side on one axis stays below, within or above it (open range;
		 * {@link #holds} rounds the camera's place, but never past a whole number).
		 */
		private void sides(int k, int relative) {
			drawHolds[k] = relative < 0 ? Float.NEGATIVE_INFINITY : relative < 16 ? 0 : 16;
			drawHolds[k + 1] = relative < 0 ? 0 : relative < 16 ? 16 : Float.POSITIVE_INFINITY;
		}

		/** Sections around the camera and those not hidden at the last test are drawn (first). */
		private void sortOut(double cx, double cy, double cz) {
			if (early.length < sectionCount) early = new boolean[sections.length];
			int leftOut = 0;
			for (int t = 0; t < sectionCount; t++) {
				double dx = Math.max(Math.max(sectionOrigin[t * 3] - cx, cx - sectionOrigin[t * 3] - 16), 0);
				double dy = Math.max(Math.max(sectionOrigin[t * 3 + 1] - cy, cy - sectionOrigin[t * 3 + 1] - 16), 0);
				double dz = Math.max(Math.max(sectionOrigin[t * 3 + 2] - cz, cz - sectionOrigin[t * 3 + 2] - 16), 0);
				early[t] = dx < 16 && dy < 16 && dz < 16 || !OcclusionCulling.hidden(((OccludedSection) sections[t]).afterburner$hiddenAt(), cx, cy, cz);
				if (!early[t]) leftOut++;
			}
			if (OcclusionCulling.measuring) OcclusionCulling.leftOutSections += leftOut;
		}

		/** The held-back draws as indirect commands, all switched off until the test sees their section, and the section tests. */
		private void writeOcclusion(double cx, double cy, double cz) {
			int commands = 0;
			for (ChunkSectionLayer layer : OCCLUDED) {
				for (Batch batch : batches[layer.ordinal()]) {
					batch.firstCommand = commands;
					commands += batch.lateSize;
				}
			}
			lateCommands = commands;
			ByteBuffer out = OcclusionCulling.commandData(commands);
			int at = 0;
			for (ChunkSectionLayer layer : OCCLUDED) {
				for (Batch batch : batches[layer.ordinal()]) {
					int bytes = batch.indexType != null ? batch.indexType.bytes : 1;
					for (int k = 0; k < batch.lateSize; k++) {
						out.putInt(at, batch.lateCounts[k]);
						out.putInt(at + 4, 0);
						out.putInt(at + 8, (int) (batch.lateOffsets[k] / bytes));
						out.putInt(at + 12, batch.lateBase[k]);
						out.putInt(at + 16, 0);
						at += OcclusionCulling.COMMAND_WORDS * 4;
					}
				}
			}
			ByteBuffer tests = OcclusionCulling.testData(sectionCount);
			for (int t = 0; t < sectionCount; t++) {
				int b = t * OcclusionCulling.TEST_WORDS * 4;
				tests.putFloat(b, (float) (sectionOrigin[t * 3] - cx));
				tests.putFloat(b + 4, (float) (sectionOrigin[t * 3 + 1] - cy));
				tests.putFloat(b + 8, (float) (sectionOrigin[t * 3 + 2] - cz));
				for (int w = 3; w < OcclusionCulling.TEST_WORDS; w++) tests.putInt(b + w * 4, 0);
			}
			for (int i = 0; i < draws; i++) {
				if (drawLateCount[i] == 0) continue;
				Batch batch = drawBatch[i];
				int b = drawSection[i] * OcclusionCulling.TEST_WORDS * 4 + (batch.layer == ChunkSectionLayer.SOLID ? 16 : 24);
				tests.putInt(b, batch.firstCommand + drawLateStart[i]);
				tests.putInt(b + 4, drawLateCount[i]);
			}
			OcclusionCulling.upload(commands, sectionCount);
			if (OcclusionCulling.measuring) OcclusionCulling.lateCommands += commands;
		}

		/** The position slot of a section whose block origin is x, y, z. */
		int info(int x, int y, int z, boolean inRegion, float visibility) {
			if (!inRegion) return add(new DynamicGpuData.ChunkSectionInfo(x, y, z, visibility));
			int rx = ChunkRegions.originX(x), ry = ChunkRegions.originY(y), rz = ChunkRegions.originZ(z);
			// A section still fading in needs its own visibility, so it gets a slot (and draw) of its own.
			if (visibility < 1.0F) return add(new DynamicGpuData.ChunkSectionInfo(rx, ry, rz, visibility));
			long key = ChunkRegions.key(x, y, z);
			int slot = regionInfo.get(key);
			if (slot < 0) {
				slot = add(new DynamicGpuData.ChunkSectionInfo(rx, ry, rz, 1.0F));
				regionInfo.put(key, slot);
			}
			return slot;
		}

		/** The slot of the region the section at x, y, z is in, made from {@code info} (fully visible) if it has none yet. */
		private int regionSlot(int x, int y, int z, DynamicGpuData.ChunkSectionInfo info) {
			long key = ChunkRegions.key(x, y, z);
			int slot = regionInfo.get(key);
			if (slot < 0) {
				slot = add(info);
				regionInfo.put(key, slot);
			}
			return slot;
		}

		private int add(DynamicGpuData.ChunkSectionInfo info) {
			infos.add(info);
			return infos.size() - 1;
		}

		private boolean ordered(ChunkSectionLayer layer) {
			return layer.translucent() && respectTranslucentOrder;
		}

		Batch batch(ChunkSectionLayer layer, int info, GpuBuffer vertexBuffer, @Nullable GpuBuffer indexBuffer, @Nullable IndexType indexType,
				int format) {
			List<Batch> list = batches[layer.ordinal()];
			if (ordered(layer)) {
				// Sections come near to far and are drawn the other way round, so only the last batch may take more.
				Batch last = list.isEmpty() ? null : list.getLast();
				if (last != null && last.matches(info, vertexBuffer, indexBuffer, indexType, format)) return last;
				if (last != null && tracked) {
					if (last.info != info) slotBreaks++;
					else if (last.vertexBuffer != vertexBuffer) bufferBreaks++;
					else if (last.indexBuffer != indexBuffer) indexBreaks++;
					else otherBreaks++;
				}
				Batch batch = newBatch(layer, info, vertexBuffer, indexBuffer, indexType, format);
				list.add(batch);
				return batch;
			}
			Batch first = byInfo[layer.ordinal()].get(info);
			for (Batch b = first; b != null; b = b.next) {
				if (b.matches(info, vertexBuffer, indexBuffer, indexType, format)) return b;
			}
			Batch batch = newBatch(layer, info, vertexBuffer, indexBuffer, indexType, format);
			if (first == null) {
				byInfo[layer.ordinal()].put(info, batch);
			} else {
				batch.next = first.next;
				first.next = batch;
			}
			list.add(batch);
			return batch;
		}

		private Batch newBatch(ChunkSectionLayer layer, int info, GpuBuffer vertexBuffer, @Nullable GpuBuffer indexBuffer, @Nullable IndexType indexType,
				int format) {
			return spare.isEmpty() ? new Batch(layer, info, vertexBuffer, indexBuffer, indexType, format)
					: spare.removeLast().set(layer, info, vertexBuffer, indexBuffer, indexType, format);
		}

		/** In place of vanilla's one draw per section. */
		public void draw(ChunkSectionLayer layer, RenderPass pass, @Nullable GpuBuffer defaultIndexBuffer, @Nullable IndexType defaultIndexType,
				@Nullable RenderPipeline pipelineOverride) {
			Probe.get().mark(TIMER_LABELS[layer.ordinal()]);
			drawBatches(layer, pass, defaultIndexBuffer, defaultIndexType, pipelineOverride, EARLY);
			if (layer == ChunkSectionLayer.CUTOUT && occlusion) {
				// Once per frame: the solid terrain is all in the depth buffer now, test the sections against it.
				occlusion = false;
				boolean tested = pipelineOverride == null && OcclusionCulling.test(sections, sectionCount, pass);
				if (lateCommands > 0) {
					if (tested) Probe.get().mark("terrain sections seen at the test");
					for (ChunkSectionLayer late : OCCLUDED) drawBatches(late, pass, defaultIndexBuffer, defaultIndexType, pipelineOverride, tested ? TESTED : UNTESTED);
				}
			}
			if (layer == ChunkSectionLayer.CUTOUT) Probe.get().opaqueDrawn();
		}

		/** Draws the layer's batches: those drawn first, or the held-back ones (with or without the test's commands). */
		private void drawBatches(ChunkSectionLayer layer, RenderPass pass, @Nullable GpuBuffer defaultIndexBuffer, @Nullable IndexType defaultIndexType,
				@Nullable RenderPipeline pipelineOverride, int which) {
			// Compact and extended meshes have pipelines of their own; a debug override (wireframe) only applies to vanilla ones.
			RenderPipeline vanilla = pipelineOverride != null ? pipelineOverride : layer.pipeline(false);
			RenderPipeline compact = CompactVertices.pipeline(layer, CompactVertices.COMPACT);
			RenderPipeline extended = CompactVertices.pipeline(layer, CompactVertices.EXTENDED);
			// Compact water is only left from before Improved Transparency was switched on (which rebuilds every section),
			// and its pipelines can't read it: left out until rebuilt.
			boolean skipCompact = pipelineOverride != null && pipelineOverride != RenderPipelines.WIREFRAME && layer.translucent();
			RenderPipeline bound = null;
			List<Batch> list = batches[layer.ordinal()];
			boolean reverse = ordered(layer);
			GpuBuffer boundIndexBuffer = null;
			IndexType boundIndexType = null;
			GpuBufferSlice boundInfo = null, boundVertices = null;
			for (int i = 0; i < list.size(); i++) {
				Batch batch = list.get(reverse ? list.size() - 1 - i : i);
				if ((which == EARLY ? batch.size : batch.lateSize) == 0 || batch.format != CompactVertices.VANILLA && skipCompact) continue;
				RenderPipeline pipeline = batch.format == CompactVertices.COMPACT && compact != null ? compact
						: batch.format == CompactVertices.EXTENDED && extended != null ? extended : vanilla;
				if (pipeline != bound) {
					pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
					bound = pipeline;
					boundVertices = null;
				}
				GpuBuffer indexBuffer = batch.indexBuffer != null ? batch.indexBuffer : defaultIndexBuffer;
				IndexType indexType = batch.indexType != null ? batch.indexType : defaultIndexType;
				if (indexBuffer == null || indexType == null) continue;
				if (indexBuffer != boundIndexBuffer || indexType != boundIndexType) {
					pass.setIndexBuffer(indexBuffer, indexType);
					boundIndexBuffer = indexBuffer;
					boundIndexType = indexType;
				}
				GpuBufferSlice info = infoSlices[batch.info];
				if (info != boundInfo) {
					pass.setUniform("ChunkSection", info);
					boundInfo = info;
				}
				if (batch.vertexSlice != boundVertices) {
					pass.setVertexBuffer(0, batch.vertexSlice);
					boundVertices = batch.vertexSlice;
				}
				if (which == EARLY) {
					if (batch.size > 0) {
						layerCalls[layer.ordinal()]++;
						if (batch.size == 1) layerSingles[layer.ordinal()]++;
					}
					batch.draw(pass, indexType, reverse);
				} else if (which == UNTESTED) {
					batch.drawLate(pass, indexType);
				} else {
					drawCalls++;
					pass.drawIndexedIndirect(OcclusionCulling.commands().slice((long) batch.firstCommand * OcclusionCulling.COMMAND_WORDS * 4,
							(long) batch.lateSize * OcclusionCulling.COMMAND_WORDS * 4), batch.lateSize);
				}
			}
		}
	}

	/** The draws of a shader pack's shadow map ({@link ShadowFrame}); there's one at a time. */
	public static ShadowFrame shadowFrame() {
		if (shadow == null) shadow = new ShadowFrame();
		return shadow;
	}

	/**
	 * The chunk draws of a shader pack's shadow map: the sections around the camera that the sun sees, every face of them (the
	 * camera's face culling doesn't hold for the sun), without occlusion culling; water and glass in any order. Built anew and
	 * patched like the main frame, but from its own list of sections.
	 */
	public static final class ShadowFrame {
		private final Frame frame = new Frame(false, false);
		/** Meshes that moved in the chunk buffers since the frame was last drawn. */
		private final ReferenceOpenHashSet<Object> moved = new ReferenceOpenHashSet<>();
		private boolean tooManyMoved, built;
		private Object[] sections = new Object[0];
		private int sectionCount, builtGeneration;
		private @Nullable SectionRenderDispatcher dispatcher;
		/** Sums up what the draws draw ({@link #contents}). */
		private long contents;

		private ShadowFrame() {
		}

		void take(ReferenceOpenHashSet<Object> meshes) {
			if (!built || tooManyMoved || meshes.isEmpty()) return;
			if (moved.size() + meshes.size() > 4096) tooManyMoved = true;
			else moved.addAll(meshes);
		}

		/**
		 * Takes in this frame's sections (any order, each once); false when there's nothing to draw. {@link #upload} before
		 * drawing them.
		 */
		public boolean prepare(List<SectionRenderDispatcher.RenderSection> list, SectionRenderDispatcher dispatcher) {
			// Also those moved since the main frame last took them in.
			take(ChunkBatcher.moved);
			int gen = generation;
			boolean rebuild = !built || tooManyMoved || dispatcher != this.dispatcher || gen != builtGeneration || !same(list);
			boolean patched = false;
			if (!rebuild && !moved.isEmpty()) {
				rebuild = !frame.patch(moved, dispatcher);
				patched = !rebuild;
			}
			moved.clear();
			tooManyMoved = false;
			if (rebuild) {
				frame.reset(false);
				// Long after any fade-in: shadows don't fade.
				frame.build(list, dispatcher, 0, Long.MAX_VALUE / 2, null);
				remember(list);
				this.dispatcher = dispatcher;
				builtGeneration = gen;
				built = true;
			}
			if (rebuild || patched) contents = frame.shadowContents();
			frame.fillAll();
			return frame.draws > 0;
		}

		/**
		 * The same while the draws are: the same meshes in the same places of the same buffers (water and glass: however sorted).
		 * It changes when a section in the shadow's box gets a new mesh, not when one elsewhere does (which builds the frame anew
		 * all the same).
		 */
		public long contents() {
			return contents;
		}

		/** Writes the sections' uniforms; once per frame the draws are drawn, before the shadow pass is opened. */
		public void upload() {
			frame.infoSlices = RenderSystem.getDynamicUniforms().writeChunkSections(frame.infoArray);
			// Grown now, not when the game next grows it: the shadow is drawn before.
			if (frame.largestIndexCount != 0) RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(frame.largestIndexCount);
		}

		/** Draws a layer into the shadow pass, whose pipelines the shader pack swaps for its shadow programs. */
		public void draw(ChunkSectionLayer layer, RenderPass pass, GpuBuffer defaultIndexBuffer, IndexType defaultIndexType) {
			frame.drawBatches(layer, pass, defaultIndexBuffer, defaultIndexType, null, EARLY);
		}

		/** Whether there's water or glass to draw. */
		public boolean hasTranslucent() {
			return frame.drawsAny(ChunkSectionLayer.TRANSLUCENT);
		}

		/** Lets go of the sections and meshes (the pack was turned off). */
		public void clear() {
			frame.reset(false);
			Arrays.fill(sections, 0, sectionCount, null);
			sectionCount = 0;
			moved.clear();
			built = tooManyMoved = false;
			dispatcher = null;
		}

		private boolean same(List<SectionRenderDispatcher.RenderSection> list) {
			int n = list.size();
			return n == sectionCount && Arrays.equals(elements(list), 0, n, sections, 0, n);
		}

		private void remember(List<SectionRenderDispatcher.RenderSection> list) {
			int n = list.size();
			if (sections.length < n) sections = new Object[Math.max(n, sections.length * 2)];
			System.arraycopy(elements(list), 0, sections, 0, n);
			if (sectionCount > n) Arrays.fill(sections, n, sectionCount, null);
			sectionCount = n;
		}
	}

	/** Which draws {@link Frame#drawBatches} makes. */
	private static final int EARLY = 0, TESTED = 1, UNTESTED = 2;
	/** Layers whose draws can wait for the occlusion test; translucent ones must keep their far-to-near order. */
	private static final ChunkSectionLayer[] OCCLUDED = {ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT};

	/** Sections of one region (or one section still fading in) in the same buffers: one draw call. */
	public static final class Batch implements SectionFaces.Sink {
		ChunkSectionLayer layer;
		boolean translucent;
		int info;
		GpuBuffer vertexBuffer;
		GpuBufferSlice vertexSlice;
		@Nullable GpuBuffer indexBuffer;
		@Nullable IndexType indexType;
		/** How its vertices are stored ({@link CompactVertices#VANILLA}, ...). */
		int format;
		@Nullable Batch next;
		private long[] indexOffsets = new long[8];
		private int[] indexCounts = new int[8], baseVertex = new int[8];
		int size;
		/** Draws held back until the occlusion test, and where their commands start in the frame's command buffer. */
		private long[] lateOffsets = new long[0];
		private int[] lateCounts = new int[0], lateBase = new int[0];
		int lateSize, firstCommand;
		/** Fill that last emptied this batch, and that found it must be filled again ({@link Frame#fill}). */
		int filledAt = -1, dirtyAt = -1;
		/** In its frame's {@link Frame#refills}. */
		boolean refill;
		/** While the next frame is built from this one's: the batch its draws go into there, and that build's stamp. */
		@Nullable Batch forward;
		int forwardAt;
		final SectionFaces.Sink late = this::addLate;

		Batch(ChunkSectionLayer layer, int info, GpuBuffer vertexBuffer, @Nullable GpuBuffer indexBuffer, @Nullable IndexType indexType, int format) {
			set(layer, info, vertexBuffer, indexBuffer, indexType, format);
		}

		/** Makes this an empty batch (of a new build), keeping its arrays. */
		Batch set(ChunkSectionLayer layer, int info, GpuBuffer vertexBuffer, @Nullable GpuBuffer indexBuffer, @Nullable IndexType indexType, int format) {
			this.layer = layer;
			this.translucent = layer.translucent();
			this.info = info;
			// A batch filled anew mostly gets the same buffer as before: its slice stays.
			if (vertexBuffer != this.vertexBuffer || vertexSlice == null) vertexSlice = vertexBuffer.slice();
			this.vertexBuffer = vertexBuffer;
			this.indexBuffer = indexBuffer;
			this.indexType = indexType;
			this.format = format;
			next = null;
			size = lateSize = firstCommand = 0;
			filledAt = dirtyAt = -1;
			return this;
		}

		/** Check mode: sums up what it holds. */
		long contents() {
			long h = size * 31L + lateSize;
			for (int k = 0; k < size; k++) h = Frame.mix(Frame.mix(h, indexOffsets[k]), (long) indexCounts[k] << 32 | baseVertex[k] & 0xFFFFFFFFL);
			for (int k = 0; k < lateSize; k++) h = Frame.mix(Frame.mix(h, lateOffsets[k]), (long) lateCounts[k] << 32 | lateBase[k] & 0xFFFFFFFFL);
			return h;
		}

		boolean matches(int info, GpuBuffer vertexBuffer, @Nullable GpuBuffer indexBuffer, @Nullable IndexType indexType, int format) {
			return this.info == info && this.vertexBuffer == vertexBuffer && this.indexBuffer == indexBuffer && this.indexType == indexType
					&& this.format == format;
		}

		@Override
		public void add(long indexOffset, int indexCount, int baseVertex) {
			if (size == indexCounts.length) {
				int n = size * 2;
				indexOffsets = Arrays.copyOf(indexOffsets, n);
				indexCounts = Arrays.copyOf(indexCounts, n);
				this.baseVertex = Arrays.copyOf(this.baseVertex, n);
			}
			indexOffsets[size] = indexOffset;
			indexCounts[size] = indexCount;
			this.baseVertex[size] = baseVertex;
			size++;
		}

		private void addLate(long indexOffset, int indexCount, int baseVertex) {
			if (lateSize == lateCounts.length) {
				int n = Math.max(8, lateSize * 2);
				lateOffsets = Arrays.copyOf(lateOffsets, n);
				lateCounts = Arrays.copyOf(lateCounts, n);
				lateBase = Arrays.copyOf(lateBase, n);
			}
			lateOffsets[lateSize] = indexOffset;
			lateCounts[lateSize] = indexCount;
			lateBase[lateSize] = baseVertex;
			lateSize++;
		}

		/** The held-back draws when the test couldn't run: all of them. */
		void drawLate(RenderPass pass, IndexType type) {
			drawCalls++;
			sectionDraws += lateSize;
			multiDraw(pass, type, lateOffsets, lateCounts, lateBase, lateSize, false);
		}

		void draw(RenderPass pass, IndexType type, boolean reverse) {
			if (size == 0) return;
			drawCalls++;
			sectionDraws += size;
			if (size == 1) {
				pass.drawIndexed(indexCounts[0], 1, (int) (indexOffsets[0] / type.bytes), baseVertex[0], 0);
				return;
			}
			multiDraw(pass, type, indexOffsets, indexCounts, baseVertex, size, reverse);
		}

		private static void multiDraw(RenderPass pass, IndexType type, long[] indexOffsets, int[] indexCounts, int[] baseVertex, int size, boolean reverse) {
			if (multiDrawKind < 0) {
				DeviceInfo device = RenderSystem.getDevice().getDeviceInfo();
				multiDrawKind = device.features().multiDrawDirectSeparate() ? SEPARATE : device.features().multiDrawDirectInterleaved() ? INTERLEAVED : ONE_BY_ONE;
				interleavedMax = Math.max(1, device.limits().maxMultiDrawDirectInterleavedDrawCount());
			}
			if (multiDrawKind == INTERLEAVED) {
				multiDrawInterleaved(pass, type, indexOffsets, indexCounts, baseVertex, size, reverse);
				return;
			}
			if (multiDrawKind == ONE_BY_ONE) {
				for (int i = 0; i < size; i++) {
					int from = reverse ? size - 1 - i : i;
					pass.drawIndexed(indexCounts[from], 1, (int) (indexOffsets[from] / type.bytes), baseVertex[from], 0);
				}
				return;
			}
			if (counts.capacity() < size) {
				int n = Math.max(size, counts.capacity() * 2);
				MemoryUtil.memFree(offsets);
				MemoryUtil.memFree(counts);
				MemoryUtil.memFree(baseVertices);
				offsets = MemoryUtil.memAllocPointer(n);
				counts = MemoryUtil.memAllocInt(n);
				baseVertices = MemoryUtil.memAllocInt(n);
			}
			for (int i = 0; i < size; i++) {
				int from = reverse ? size - 1 - i : i;
				offsets.put(i, indexOffsets[from]);
				counts.put(i, indexCounts[from]);
				baseVertices.put(i, baseVertex[from]);
			}
			offsets.clear();
			counts.clear();
			baseVertices.clear();
			pass.multiDrawIndexed(offsets, counts, baseVertices, size);
		}

		private static void multiDrawInterleaved(RenderPass pass, IndexType type, long[] indexOffsets, int[] indexCounts, int[] baseVertex, int size,
				boolean reverse) {
			if (interleaved.capacity() < size * 3) {
				MemoryUtil.memFree(interleaved);
				interleaved = MemoryUtil.memAllocInt(Math.max(size * 3, interleaved.capacity() * 2));
			}
			for (int i = 0; i < size; i++) {
				int from = reverse ? size - 1 - i : i;
				interleaved.put(i * 3, (int) (indexOffsets[from] / type.bytes));
				interleaved.put(i * 3 + 1, indexCounts[from]);
				interleaved.put(i * 3 + 2, baseVertex[from]);
			}
			for (int at = 0; at < size; at += interleavedMax) {
				int n = Math.min(interleavedMax, size - at);
				interleaved.limit((at + n) * 3).position(at * 3);
				pass.multiDrawIndexed(interleaved, 1, 0, n);
			}
			interleaved.clear();
		}
	}
}
