package com.afterburner.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Two looks through every visible section the game takes each frame, left out while what they'd find can't have changed
 * (at render distance 32 that's thousands of sections a look, every frame):
 * <ul>
 * <li>for sections to rebuild: none was marked since the last look and no chunk's light was turned on. In a look, a
 * section whose light was off isn't asked again until some chunk's light is turned on (the light lookup was most of
 * the look: at the edge of a long render distance many sections wait for theirs);</li>
 * <li>for block entities to draw: only the sections that have some are looked through, found again only when the visible
 * sections change or a section's build gains or loses block entities.</li>
 * </ul>
 * Render thread only, but for the counters, which builds bump from any thread.
 */
public final class SectionScans {
	private static final ObjectArrayList<SectionRenderDispatcher.RenderSection> NONE = new ObjectArrayList<>();
	/** Bumped when a section is marked for a rebuild. */
	private static final AtomicInteger marked = new AtomicInteger();
	/** Bumped when a section's build has block entities or had some. */
	private static final AtomicInteger blockEntityBuilds = new AtomicInteger();

	/** Bumped when a chunk's light is turned on or off: only then can a section's light answer change. */
	private static final AtomicInteger lightSwitches = new AtomicInteger(1);
	/** The last switch of each chunk switched on the render thread (by ChunkPos key); render thread only. */
	private static final Long2IntOpenHashMap chunkSwitched = new Long2IntOpenHashMap();
	/** Every section's light is asked again if it was found off before this switch (one switched off the render thread, ...). */
	private static volatile int allSwitched = 1;

	private static final Seen rebuildSeen = new Seen();
	private static int rebuildMarked = -1, rebuildLight = -1;

	private static final Seen blockEntitySeen = new Seen();
	private static int blockEntityStamp = -1;
	private static final ObjectArrayList<SectionRenderDispatcher.RenderSection> WITH_BLOCK_ENTITIES = new ObjectArrayList<>();

	private SectionScans() {
	}

	public static void markedForRebuild() {
		marked.incrementAndGet();
	}

	public static void lightSwitched(long chunk) {
		int n = lightSwitches.incrementAndGet();
		if (!RenderSystem.isOnRenderThread()) {
			allSwitched = n;
		} else if (chunkSwitched.size() >= 1 << 16) {
			chunkSwitched.clear();
			allSwitched = n;
		} else {
			chunkSwitched.put(chunk, n);
		}
	}

	public static void blockEntitiesChanged() {
		blockEntityBuilds.incrementAndGet();
	}

	/** The sections the rebuild look goes through: none when nothing it would find can have changed. */
	public static ObjectArrayList<SectionRenderDispatcher.RenderSection> toRebuild(ObjectArrayList<SectionRenderDispatcher.RenderSection> visible) {
		int now = marked.get(), light = lightSwitches.get();
		if (now == rebuildMarked && light == rebuildLight && rebuildSeen.same(visible)) return NONE;
		rebuildMarked = now;
		rebuildLight = light;
		rebuildSeen.take(visible);
		return visible;
	}

	/**
	 * In the rebuild look, whether a marked section's light is known to be off: it was off when last asked, and neither its
	 * chunk's light nor (off the render thread) any chunk's was switched since. Its light stays off until its chunk's is turned on.
	 */
	public static boolean lightKnownOff(LightWaitSection section, long chunk) {
		int off = section.afterburner$lightOffAt();
		return off != 0 && off >= allSwitched && chunkSwitched.get(chunk) <= off;
	}

	/** What the light lookup answered for a section: off is remembered until a switch that may turn it on. */
	public static void lightAsked(LightWaitSection section, boolean on) {
		section.afterburner$setLightOffAt(on ? 0 : lightSwitches.get());
	}

	/** The visible sections that have block entities, in the same order. */
	public static ObjectArrayList<SectionRenderDispatcher.RenderSection> withBlockEntities(ObjectArrayList<SectionRenderDispatcher.RenderSection> visible) {
		int now = blockEntityBuilds.get();
		if (now == blockEntityStamp && blockEntitySeen.same(visible)) return WITH_BLOCK_ENTITIES;
		blockEntityStamp = now;
		blockEntitySeen.take(visible);
		WITH_BLOCK_ENTITIES.clear();
		Object[] elements = visible.elements();
		for (int i = 0, n = visible.size(); i < n; i++) {
			SectionRenderDispatcher.RenderSection section = (SectionRenderDispatcher.RenderSection) elements[i];
			if (!section.getCompiled().getRenderableBlockEntities().isEmpty()) WITH_BLOCK_ENTITIES.add(section);
		}
		return WITH_BLOCK_ENTITIES;
	}

	/** A copy of the visible list as it was at a look, to tell whether it's the same now. */
	private static final class Seen {
		private Object[] elements = new Object[0];
		private int count = -1;

		boolean same(ObjectArrayList<SectionRenderDispatcher.RenderSection> list) {
			int n = list.size();
			return n == count && Arrays.equals(list.elements(), 0, n, elements, 0, n);
		}

		void take(ObjectArrayList<SectionRenderDispatcher.RenderSection> list) {
			int n = list.size();
			if (elements.length < n) elements = new Object[Math.max(n, elements.length * 2)];
			System.arraycopy(list.elements(), 0, elements, 0, n);
			if (count > n) Arrays.fill(elements, n, count, null);
			count = n;
		}
	}
}
