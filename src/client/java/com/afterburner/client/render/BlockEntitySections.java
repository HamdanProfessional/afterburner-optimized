package com.afterburner.client.render;

import com.afterburner.Afterburner;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

import java.util.Arrays;

/**
 * The visible sections that have block entities to draw (chests, signs, banners...), for the game's per-frame look
 * through every visible section's mesh. Found again only when the visible sections or a section's mesh change, and
 * then each section remembers whether its mesh has any ({@link BuiltSection#afterburner$hasBlockEntities}): while flying a
 * mesh changes nearly every frame, and reading every section's mesh again was most of a millisecond.
 */
public final class BlockEntitySections {
	private static final ObjectArrayList<SectionRenderDispatcher.RenderSection> WITH_BLOCK_ENTITIES = new ObjectArrayList<>();
	private static Object[] seen = new Object[0];
	private static int seenCount = -1, seenGeneration;
	/** {@code -Dafterburner.checkBlockEntities=true} also reads every mesh and logs where that finds otherwise. */
	private static final boolean CHECK = Boolean.getBoolean("afterburner.checkBlockEntities");
	/** {@code -Dafterburner.blockEntityMemo=false} reads every section's mesh each time, as before. */
	private static final boolean MEMO = !"false".equals(System.getProperty("afterburner.blockEntityMemo"));
	public static long checks, different;

	private BlockEntitySections() {
	}

	/** Render thread only. */
	public static ObjectArrayList<SectionRenderDispatcher.RenderSection> of(ObjectArrayList<SectionRenderDispatcher.RenderSection> visible) {
		int generation = ChunkBatcher.generation();
		int n = visible.size();
		Object[] elements = visible.elements();
		if (generation == seenGeneration && n == seenCount && Arrays.equals(elements, 0, n, seen, 0, n)) return WITH_BLOCK_ENTITIES;
		WITH_BLOCK_ENTITIES.clear();
		for (int i = 0; i < n; i++) {
			SectionRenderDispatcher.RenderSection section = (SectionRenderDispatcher.RenderSection) elements[i];
			if (MEMO ? ((BuiltSection) section).afterburner$hasBlockEntities() : !section.getSectionMesh().getRenderableBlockEntities().isEmpty()) {
				WITH_BLOCK_ENTITIES.add(section);
			}
		}
		if (CHECK) check(elements, n);
		if (seen.length < n) seen = new Object[Math.max(n, seen.length * 2)];
		System.arraycopy(elements, 0, seen, 0, n);
		if (seenCount > n) Arrays.fill(seen, n, seenCount, null);
		seenCount = n;
		seenGeneration = generation;
		return WITH_BLOCK_ENTITIES;
	}

	/** Check mode: the sections reading every mesh finds, against those remembered. A mesh set on a worker meanwhile can differ. */
	private static void check(Object[] elements, int n) {
		int k = 0;
		boolean same = true;
		for (int i = 0; i < n && same; i++) {
			SectionRenderDispatcher.RenderSection section = (SectionRenderDispatcher.RenderSection) elements[i];
			if (section.getSectionMesh().getRenderableBlockEntities().isEmpty()) continue;
			same = k < WITH_BLOCK_ENTITIES.size() && WITH_BLOCK_ENTITIES.get(k) == section;
			k++;
		}
		same &= k == WITH_BLOCK_ENTITIES.size();
		checks++;
		if (!same && ++different <= 20) {
			Afterburner.LOGGER.error("Block entity check failed: {} sections remembered, {} found reading every mesh", WITH_BLOCK_ENTITIES.size(), k);
		}
	}
}
