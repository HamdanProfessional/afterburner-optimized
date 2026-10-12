package com.afterburner.client.render;

import com.afterburner.client.mixin.render.ViewAreaAccessor;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.phys.Vec3;
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

	/** This update's view area and the sections in view so far. */
	private @Nullable ViewArea area;
	private SectionRenderDispatcher.RenderSection @Nullable [] sections;
	private SectionOcclusionGraph.@Nullable SectionToNodeMap nodes;
	private int gridX, gridY, gridZ, minBuildHeight, maxBuildHeight;
	private final BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
	/** Where the last walk ended: after the step that was stopped. */
	public double x, y, z;
	/** The step of the line last handed in. If vanilla comes back with the same one, it is left to vanilla. */
	public @Nullable Vec3 last;
	/** Lines, lines answered without a step, lines picked up where they were stopped, steps walked; checked and different in check mode. */
	public long lines, remembered, resumed, walked, checked, different;

	private OcclusionRays() {
	}

	/** An update starts on this thread, with this view area and node map: nothing remembered from before counts. */
	public static OcclusionRays begin(ViewArea area, SectionOcclusionGraph.SectionToNodeMap nodes, Vec3 camera) {
		return THREAD.get().start(area, nodes, camera);
	}

	private OcclusionRays start(ViewArea area, SectionOcclusionGraph.SectionToNodeMap nodes, Vec3 camera) {
		ViewAreaAccessor grid = (ViewAreaAccessor) area;
		this.area = area;
		this.nodes = nodes;
		sections = area.sections;
		gridX = grid.afterburner$sizeX();
		gridY = grid.afterburner$sizeY();
		gridZ = grid.afterburner$sizeZ();
		LevelHeightAccessor height = area.getLevelHeightAccessor();
		minBuildHeight = height.getMinBuildHeight();
		maxBuildHeight = height.getMaxBuildHeight();
		int radius = area.getViewDistance();
		// Corners of the sections around the camera, with a section to spare: the view area can lag the camera a little.
		minX = SectionPos.posToSectionCoord(camera.x) - radius - 2;
		minZ = SectionPos.posToSectionCoord(camera.z) - radius - 2;
		minY = SectionPos.blockToSectionCoord(minBuildHeight);
		sizeX = sizeZ = 2 * radius + 6;
		sizeY = gridY + 2;
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
		area = null;
		sections = null;
		nodes = null;
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

	/** Steps known to get through from this corner (0 if nothing is known), or {@link #THROUGH}. */
	public int known(int index) {
		return index >= 0 && at[index] == stamp ? progress[index] : 0;
	}

	public void remember(int index, int steps) {
		if (index < 0) return;
		progress[index] = steps;
		at[index] = stamp;
	}

	/**
	 * Whether the section holding this block is in view so far: vanilla's
	 * {@code sectionToNodeMap.get(viewArea.getRenderSectionAt(pos)) != null}, with the view area's grid looked up here.
	 */
	private boolean inView(int bx, int by, int bz) {
		int i = Math.floorDiv(by - minBuildHeight, 16);
		if (i < 0 || i >= gridY) return false;
		int j = Math.floorMod(bx >> 4, gridX), k = Math.floorMod(bz >> 4, gridZ);
		SectionRenderDispatcher.RenderSection section = sections[(k * gridY + i) * gridX + j];
		return section != null && nodes.get(section) != null;
	}

	/** The same through the view area itself, for check mode. */
	private boolean inViewVanilla(int bx, int by, int bz) {
		SectionRenderDispatcher.RenderSection section = ((ViewAreaAccessor) area).afterburner$sectionAt(block.set(bx, by, bz));
		return section != null && nodes.get(section) != null;
	}

	/**
	 * Vanilla's walk from a corner along {@code step}, skipping the checks of the first {@code from} steps, which got through
	 * before. Returns {@link #THROUGH}, or how many steps get through, with {@link #x}, {@link #y}, {@link #z} left after the
	 * step that is stopped. The same arithmetic as vanilla's {@code Vec3} loop, so the same points.
	 */
	public int walk(Vec3 corner, Vec3 step, Vec3 camera, int from, boolean vanilla) {
		double px = corner.x, py = corner.y, pz = corner.z;
		double sx = step.x, sy = step.y, sz = step.z;
		for (int i = 0; i < from; i++) {
			px += sx;
			py += sy;
			pz += sz;
		}
		int through = from;
		while (true) {
			double dx = camera.x - px, dy = camera.y - py, dz = camera.z - pz;
			if (dx * dx + dy * dy + dz * dz <= 3600.0) return THROUGH;
			px += sx;
			py += sy;
			pz += sz;
			walked++;
			if (py > maxBuildHeight || py < minBuildHeight) return THROUGH;
			int bx = Mth.floor(px), by = Mth.floor(py), bz = Mth.floor(pz);
			if (!(vanilla ? inViewVanilla(bx, by, bz) : inView(bx, by, bz))) {
				x = px;
				y = py;
				z = pz;
				return through;
			}
			through++;
		}
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
