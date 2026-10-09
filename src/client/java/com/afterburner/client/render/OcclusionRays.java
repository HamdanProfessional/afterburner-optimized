package com.afterburner.client.render;

import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Past 60 blocks, vanilla's visibility update only lets a chunk section into view if a line from one of its corners to
 * the camera runs through sections already in view. It walks that line in 28-block steps, for every way out of every
 * section, so up to six times a section, and neighbouring sections share corners: most walks are repeats. Within one
 * update, sections only ever join the view, so a line that got through still gets through, and one that was stopped
 * still gets as far as before. This remembers, for each corner, how far its line is known to get, and walks only the
 * rest. Same sections in view as vanilla. See {@link com.afterburner.client.mixin.render.OcclusionRayMixin}.
 */
public final class OcclusionRays {
	/** For testing: with {@code -Dafterburner.checkCulling=true}, every line is also walked in full and compared. */
	public static final boolean CHECK = Boolean.getBoolean("afterburner.checkCulling");
	/** Vanilla's step length. */
	public static final double STEP = Math.ceil(Math.sqrt(3.0) * 16.0);
	/** A line known to get through to the camera. */
	public static final int THROUGH = -1;
	/** Every thread's lines, for benchmark reports. */
	private static final Queue<OcclusionRays> ALL = new ConcurrentLinkedQueue<>();
	private static final ThreadLocal<OcclusionRays> THREAD = ThreadLocal.withInitial(() -> {
		OcclusionRays rays = new OcclusionRays();
		ALL.add(rays);
		return rays;
	});

	/** For each corner: steps known to get through, or {@link #THROUGH}; only counts where {@link #at} is this update's stamp. */
	private int[] progress = new int[0], at = new int[0];
	private int stamp;
	private int minX, minY, minZ, sizeX, sizeY, sizeZ;

	/** This update's view area sections and the sections in view so far ({@link #inView}). */
	private @Nullable RotatingSectionStorage<SectionRenderDispatcher.RenderSection> grid;
	private SectionOcclusionGraph.Node @Nullable [] nodes;
	private int radius, across, high, lowestY;
	/** The view area's centre last looked at, and what follows from it. */
	private @Nullable SectionPos center;
	private int lowX, lowZ, modX, modZ;
	/** Scratch for walking a line. */
	public final Vector3d pos = new Vector3d(), step = new Vector3d();
	public final BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
	/** The line last handed in. If vanilla comes back with the same one, it is left to vanilla. */
	public @Nullable Object last;
	/** Lines, lines answered without a step, lines picked up where they were stopped, steps walked; checked and different in check mode. */
	public long lines, remembered, resumed, walked, checked, different;

	private OcclusionRays() {
	}

	/** An update starts on this thread, with these view area sections and nodes: nothing remembered from before counts. */
	public static OcclusionRays begin(RotatingSectionStorage<SectionRenderDispatcher.RenderSection> grid, SectionOcclusionGraph.Node[] nodes, Vec3 camera) {
		return THREAD.get().start(grid, nodes, camera);
	}

	private OcclusionRays start(RotatingSectionStorage<SectionRenderDispatcher.RenderSection> grid, SectionOcclusionGraph.Node[] nodes, Vec3 camera) {
		this.grid = grid;
		this.nodes = nodes;
		radius = grid.radius();
		across = 2 * radius + 1;
		high = grid.height();
		lowestY = grid.minY();
		center = null;
		int minSectionY = grid.minY(), maxSectionY = grid.maxY();
		// Corners of the sections around the camera, with a section to spare: the view area can lag the camera a little.
		minX = SectionPos.posToSectionCoord(camera.x) - radius - 2;
		minZ = SectionPos.posToSectionCoord(camera.z) - radius - 2;
		minY = minSectionY;
		sizeX = sizeZ = 2 * radius + 6;
		sizeY = maxSectionY - minSectionY + 2;
		int size = sizeX * sizeY * sizeZ;
		if (at.length < size) {
			progress = new int[size];
			at = new int[size];
			stamp = 0;
		}
		if (++stamp == 0) {
			Arrays.fill(at, 0);
			stamp = 1;
		}
		last = null;
		return this;
	}

	/** The update is over: what it was for isn't held on to. */
	public void end() {
		grid = null;
		nodes = null;
		center = null;
		last = null;
	}

	/** Where a corner is kept, or -1. Corners are section corners, so whole multiples of 16. */
	public int index(double x, double y, double z) {
		int ix = (int) x, iy = (int) y, iz = (int) z;
		if (ix != x || iy != y || iz != z || ((ix | iy | iz) & 15) != 0) return -1;
		ix = (ix >> 4) - minX;
		iy = (iy >> 4) - minY;
		iz = (iz >> 4) - minZ;
		return ix >= 0 && ix < sizeX && iy >= 0 && iy < sizeY && iz >= 0 && iz < sizeZ ? (iy * sizeZ + iz) * sizeX + ix : -1;
	}

	/**
	 * Whether the section at these section coordinates is in view so far. The same as vanilla's
	 * {@code sectionToNodeMap.get(viewArea.getRenderSectionAt(pos)) != null}, without going through the section: a view
	 * area section's index is its place in the grid, so its node is at that place too.
	 */
	public boolean inView(int x, int y, int z) {
		SectionPos at = grid.centerSectionPos();
		if (at != center) {
			center = at;
			lowX = at.x() - radius;
			lowZ = at.z() - radius;
			modX = Math.floorMod(lowX, across);
			modZ = Math.floorMod(lowZ, across);
		}
		int dx = x - lowX, dy = y - lowestY, dz = z - lowZ;
		if (dx < 0 || dx >= across || dy < 0 || dy >= high || dz < 0 || dz >= across) return false;
		// Math.floorMod(x, across), knowing x is within the grid
		int gx = modX + dx, gz = modZ + dz;
		if (gx >= across) gx -= across;
		if (gz >= across) gz -= across;
		int index = (gz * high + dy) * across + gx;
		return index < nodes.length && nodes[index] != null;
	}

	/** Steps known to get through from this corner (0 if nothing is known), or {@link #THROUGH}. */
	public int known(int index) {
		return index >= 0 && at[index] == stamp ? progress[index] : 0;
	}

	public void remember(int index, int steps) {
		if (index < 0) return;
		progress[index] = steps;
		at[index] = stamp;
	}

	public static void reset() {
		for (OcclusionRays rays : ALL) rays.lines = rays.remembered = rays.resumed = rays.walked = rays.checked = rays.different = 0;
	}

	/** For benchmark reports, e.g. "Visibility lines: 1,234,567, 61.0% answered from memory, 20.2% picked up where they stopped, 1.3 steps walked each". */
	public static String summary() {
		long lines = 0, remembered = 0, resumed = 0, walked = 0, checked = 0, different = 0;
		for (OcclusionRays rays : ALL) {
			lines += rays.lines;
			remembered += rays.remembered;
			resumed += rays.resumed;
			walked += rays.walked;
			checked += rays.checked;
			different += rays.different;
		}
		String line = "Visibility lines: " + String.format("%,d", lines) + ", "
				+ String.format("%.1f", lines == 0 ? 0.0 : 100.0 * remembered / lines) + "% answered from memory, "
				+ String.format("%.1f", lines == 0 ? 0.0 : 100.0 * resumed / lines) + "% picked up where they stopped, "
				+ String.format("%.1f", lines == 0 ? 0.0 : (double) walked / lines) + " steps walked each";
		if (CHECK) line += "; check: " + String.format("%,d", checked) + " compared, " + different + " different";
		return line;
	}
}
