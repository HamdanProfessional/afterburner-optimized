package com.afterburner.client.render;

import com.afterburner.client.mixin.render.FrustumAccessor;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.joml.FrustumIntersection;

import java.util.List;

/**
 * The frustum test of one pass over the occlusion graph's sections, a region (8 x 4 x 8 sections) at a time: each region
 * the pass meets is tested once, and one wholly in or wholly out of view answers for its sections; only the sections of
 * regions the frustum's edge goes through are tested one by one. At render distance 32 that's most of the regions in
 * view, so most of the per-section tests are left out. The pass walks the graph's flat copy ({@link RegionList}) when
 * it's up to date, so a section is only looked at when its region is partly in view. Render thread only.
 */
public final class RegionFrustum {
	public static final int OUTSIDE = 0, INSIDE = 1, PARTLY = 2;
	/** Slots: 64 x 64 regions across (render distance 32 is 9 of them), 16 up; each remembers which region it holds. */
	private static final int SLOTS = 64 * 64 * 16;
	private static final long[] keys = new long[SLOTS];
	private static final int[] passes = new int[SLOTS];
	private static final byte[] answers = new byte[SLOTS];
	private static int pass;
	/** Time spent in the game's passes, and their number, for the perf log ({@code -Dafterburner.perfLog=true}). */
	public static long nanos, passCount, fastPasses, walked;
	private static long started;
	/** Whether this pass's frustum is vanilla's own; a subclass (Afterburner's look everywhere, another mod's) tests its own way. */
	private static boolean plain;
	private static FrustumIntersection planes;
	private static double camX, camY, camZ;
	/** Bumped when the sections are moved to follow the camera (any thread reads it). */
	private static volatile int moves;

	private RegionFrustum() {
	}

	public static void moved() {
		moves++;
	}

	public static int moves() {
		return moves;
	}

	/** The region of the section with this corner, as one number. */
	public static long key(int x, int y, int z) {
		return ((long) (x >> 7) & 0x3FFFFF) << 42 | ((long) (z >> 7) & 0x3FFFFF) << 20 | ((y >> 6) & 0xFFFFF);
	}

	/** Start of a pass, with the frustum it tests against. */
	public static void begin(Frustum frustum) {
		plain = frustum.getClass() == Frustum.class;
		if (!plain) return;
		started = System.nanoTime();
		FrustumAccessor f = (FrustumAccessor) frustum;
		planes = f.afterburner$intersection();
		camX = f.afterburner$camX();
		camY = f.afterburner$camY();
		camZ = f.afterburner$camZ();
		pass++;
	}

	/** End of a pass. */
	public static void end() {
		if (!plain) return;
		nanos += System.nanoTime() - started;
		passCount++;
	}

	/**
	 * The pass over a graph's flat copy: the sections in view added to {@code out} in the graph's order. False (nothing
	 * added) when the copy can't be used: another frustum, or sections were added past it. The copy's corners are read
	 * again first if sections were moved since ({@link RegionList#afterburner$refresh}).
	 */
	public static boolean pass(RegionList graph, int graphSize, Frustum frustum, List<SectionRenderDispatcher.RenderSection> out) {
		if (!plain || graph.afterburner$count() != graphSize) return false;
		graph.afterburner$refresh();
		fastPasses++;
		walked += graphSize;
		SectionRenderDispatcher.RenderSection[] sections = graph.afterburner$sections();
		long[] regionKeys = graph.afterburner$keys();
		int[] origins = graph.afterburner$origins();
		long lastKey = 0;
		int last = -1;
		for (int i = 0; i < graphSize; i++) {
			long key = regionKeys[i];
			// The graph goes out from the camera, so neighbours are often in the same region.
			int answer = key == lastKey && last >= 0 ? last : answer(key);
			lastKey = key;
			last = answer;
			if (answer == INSIDE || answer == PARTLY && inView(origins[i * 3], origins[i * 3 + 1], origins[i * 3 + 2])) out.add(sections[i]);
		}
		return true;
	}

	/** Vanilla's test of a section's box (Frustum.cubeInFrustum, same float math), from its corner rather than its box. */
	private static boolean inView(int x, int y, int z) {
		float minX = (float) (x - camX), minY = (float) (y - camY), minZ = (float) (z - camZ);
		float maxX = (float) (x + 16 - camX), maxY = (float) (y + 16 - camY), maxZ = (float) (z + 16 - camZ);
		return planes.testAab(minX, minY, minZ, maxX, maxY, maxZ);
	}

	/** Whether the region of the section with this box is wholly in view, wholly out of it, or partly in. */
	public static int region(AABB box) {
		if (!plain) return PARTLY;
		return answer(key(Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ)));
	}

	private static int answer(long key) {
		int rx = (int) (key >> 42), rz = (int) (key << 22 >> 42), ry = (int) (key << 44 >> 44);
		int slot = ((rx & 63) << 10 | (rz & 63) << 4) | (ry & 15);
		if (passes[slot] == pass && keys[slot] == key) return answers[slot];
		double x = (double) rx * 128, y = (double) ry * 64, z = (double) rz * 128;
		// The same float math as vanilla's section test (Frustum.cubeInFrustum), on the region's box.
		int r = planes.intersectAab((float) (x - camX), (float) (y - camY), (float) (z - camZ),
				(float) (x + 128 - camX), (float) (y + 64 - camY), (float) (z + 128 - camZ));
		int answer = r == FrustumIntersection.INSIDE ? INSIDE : r == FrustumIntersection.INTERSECT ? PARTLY : OUTSIDE;
		passes[slot] = pass;
		keys[slot] = key;
		answers[slot] = (byte) answer;
		return answer;
	}
}
