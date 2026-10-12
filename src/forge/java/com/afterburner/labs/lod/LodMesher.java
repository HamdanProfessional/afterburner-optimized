package com.afterburner.labs.lod;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

/**
 * Turns a node of the far terrain (8 x 8 sheets: 128 x 128 columns at one level) into quads: a face wherever a solid or
 * water voxel meets open air, faces of one color and light next to each other merged into one, and along the node's edge
 * "skirts" a few voxels down even where the neighbor is solid, which close the cracks where a node meets one of another
 * level. At levels 0 and 1 quads stop at chunk borders, so the vertex shader can hide them chunk by chunk where the
 * game draws its own chunks. Worker thread only.
 * <p>
 * A vertex is 8 bytes: x and z (0 to 128) in voxels from the node's corner and the face (in
 * {@link net.minecraft.core.Direction} order) as {@code (z * 129 + x) * 6 + face} in bits 0-16, the block's material for
 * shader packs (17-19, {@link LodKinds#material}) and y (20-31) in voxels from the node's corner; then the color (RGB) and
 * the light (sky light in the high 4 bits of alpha). Water's quads come last, so a shader pack can draw them on their own
 * (see-through).
 */
final class LodMesher {
	static final int NODE = 128, SHEETS = NODE / LodSheet.SIZE;
	private static final int W = NODE + 2, SKIRT = 8;
	private static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;

	/**
	 * A built node: {@code vertices} is native memory the renderer frees after uploading it (0 when there are no quads). The
	 * last {@code waterQuads} of its quads are water's.
	 */
	record Mesh(int level, int nx, int nz, int version, long vertices, int quads, int waterQuads, int minY, int maxY) {
		void free() {
			if (vertices != 0) MemoryUtil.nmemFree(vertices);
		}
	}

	private final LodColors colors = new LodColors();
	private final long[] @Nullable [] grid = new long[W * W][];
	private final int[] gridBiomes = new int[W * W];
	private final long[][] faces = new long[6][1024];
	private final int[] faceCounts = new int[6];
	private final Long2IntOpenHashMap keyIds = new Long2IntOpenHashMap();
	private final LongArrayList keys = new LongArrayList();
	private int[] out = new int[4096], waterOut = new int[1024];
	private int outCount, waterCount, minY, maxY;
	private int[] ox0 = new int[NODE], ox1 = new int[NODE], oz0 = new int[NODE], okey = new int[NODE];
	private int[] nx0 = new int[NODE], nx1 = new int[NODE], nz0 = new int[NODE], nkey = new int[NODE];
	private final int[] sx0 = new int[NODE], sx1 = new int[NODE], skey = new int[NODE];
	private LodWorld world;
	private boolean skyDim;
	private int virtualWhat;

	LodMesher() {
		keyIds.defaultReturnValue(-1);
	}

	/** Null if there are no block colors to build it with yet. */
	@Nullable Mesh build(LodWorld world, int level, int nx, int nz, int version) {
		if (!colors.begin(world)) return null;
		this.world = world;
		skyDim = world.skyLight;
		virtualWhat = LodSheet.air(skyDim ? 15 : 0, 0);
		fill(level, nx, nz);
		Arrays.fill(faceCounts, 0);
		keyIds.clear();
		keys.clear();
		for (int z = 0; z < NODE; z++) {
			for (int x = 0; x < NODE; x++) column(x, z);
		}
		outCount = 0;
		waterCount = 0;
		minY = Integer.MAX_VALUE;
		maxY = Integer.MIN_VALUE;
		int chunk = level <= 1 ? 16 >> level : 0;
		for (int dir = 0; dir < 6; dir++) {
			long[] f = faces[dir];
			int n = faceCounts[dir];
			Arrays.sort(f, 0, n);
			if (dir == UP || dir == DOWN) mergeFlat(dir, f, n, chunk);
			else mergeSides(dir, f, n, chunk);
		}
		Arrays.fill(grid, null);
		int quads = (outCount + waterCount) / 8;
		if (quads == 0) return new Mesh(level, nx, nz, version, 0, 0, 0, 0, 0);
		long memory = MemoryUtil.nmemAlloc((long) (outCount + waterCount) * 4);
		MemoryUtil.memIntBuffer(memory, outCount + waterCount).put(out, 0, outCount).put(waterOut, 0, waterCount);
		return new Mesh(level, nx, nz, version, memory, quads, waterCount / 8, minY, maxY);
	}

	private void fill(int level, int nx, int nz) {
		int baseX = nx * SHEETS, baseZ = nz * SHEETS;
		for (int dz = -1; dz <= SHEETS; dz++) {
			for (int dx = -1; dx <= SHEETS; dx++) {
				if ((dx == -1 || dx == SHEETS) && (dz == -1 || dz == SHEETS)) continue;
				LodSheet sheet = world.get(level, baseX + dx, baseZ + dz);
				if (sheet == null) continue;
				for (int z = 0; z < LodSheet.SIZE; z++) {
					int gz = dz * LodSheet.SIZE + z + 1;
					if (gz < 0 || gz >= W) continue;
					for (int x = 0; x < LodSheet.SIZE; x++) {
						int gx = dx * LodSheet.SIZE + x + 1;
						if (gx < 0 || gx >= W) continue;
						grid[gz * W + gx] = sheet.columns[z * LodSheet.SIZE + x];
						gridBiomes[gz * W + gx] = sheet.biomes[z * LodSheet.SIZE + x] & LodSheet.BIOME;
					}
				}
			}
		}
	}

	private boolean open(int what) {
		return LodSheet.state(what) == LodSheet.AIR && (!skyDim || LodSheet.sky(what) > 0);
	}

	/** The light a face gets from the open air in front of it; lava glows. */
	private static int light(int light, byte kind) {
		return kind == LodKinds.LAVA ? light & 0xF0 | 15 : light & 0xFF;
	}

	private void column(int x, int z) {
		int g = (z + 1) * W + x + 1;
		long[] col = grid[g];
		if (col == null) return;
		int biome = gridBiomes[g];
		int n = col.length;
		int surfaceTop = 0, topLight = virtualWhat & 0xFF;
		for (int r = n - 1; r >= 0; r--) {
			if (world.kind(LodSheet.state(LodSheet.what(col[r]))) != LodKinds.EMPTY) {
				surfaceTop = LodSheet.top(col[r]);
				topLight = (r + 1 < n ? LodSheet.what(col[r + 1]) : virtualWhat) & 0xFF;
				break;
			}
		}
		int bottom = 0;
		for (int r = 0; r < n; r++) {
			int what = LodSheet.what(col[r]), top = LodSheet.top(col[r]);
			int state = LodSheet.state(what);
			byte kind = world.kind(state);
			if (kind != LodKinds.EMPTY) {
				int above = r + 1 < n ? LodSheet.what(col[r + 1]) : virtualWhat;
				if (open(above)) flat(UP, top - 1, z, x, key(colors.color(world, state, biome, UP), light(above, kind), state));
				if (r > 0) {
					int below = LodSheet.what(col[r - 1]);
					if (open(below)) flat(DOWN, bottom, z, x, key(colors.color(world, state, biome, DOWN), light(below, kind), state));
				}
			}
			bottom = top;
		}
		side(NORTH, col, grid[z * W + x + 1], z, x, z == 0, surfaceTop, topLight, biome);
		side(SOUTH, col, grid[(z + 2) * W + x + 1], z, x, z == NODE - 1, surfaceTop, topLight, biome);
		side(WEST, col, grid[(z + 1) * W + x], x, z, x == 0, surfaceTop, topLight, biome);
		side(EAST, col, grid[(z + 1) * W + x + 2], x, z, x == NODE - 1, surfaceTop, topLight, biome);
	}

	/**
	 * The faces of a column on one side, walking its runs alongside the neighbor's: where the neighbor is open air, and on
	 * the node's edge or next to a column that wasn't seen down to {@link #SKIRT} voxels below the surface.
	 */
	private void side(int dir, long[] self, long @Nullable [] neighbor, int plane, int tangent, boolean edge, int surfaceTop, int topLight, int biome) {
		int skirtFrom = edge || neighbor == null ? Math.max(0, surfaceTop - SKIRT) : Integer.MAX_VALUE;
		// The same column next door (flat land, sea): solid only ever meets solid, so there's nothing to draw between them.
		if (skirtFrom == Integer.MAX_VALUE && Arrays.equals(self, neighbor)) return;
		int i = 0, j = 0, y = 0;
		int pendingKey = -1, pendingY0 = 0, pendingY1 = 0;
		while (i < self.length) {
			long r = self[i];
			int rTop = LodSheet.top(r);
			int nWhat, nTop;
			if (neighbor == null) {
				nWhat = LodSheet.HIDDEN << 8;
				nTop = Integer.MAX_VALUE;
			} else if (j >= neighbor.length) {
				nWhat = virtualWhat;
				nTop = Integer.MAX_VALUE;
			} else {
				nWhat = LodSheet.what(neighbor[j]);
				nTop = LodSheet.top(neighbor[j]);
			}
			int top = Math.min(rTop, nTop);
			int state = LodSheet.state(LodSheet.what(r));
			byte kind = world.kind(state);
			if (kind != LodKinds.EMPTY && top > y) {
				int key = -1, y0 = y;
				if (open(nWhat)) {
					key = key(colors.color(world, state, biome, dir), light(nWhat, kind), state);
				} else if (top > skirtFrom) {
					y0 = Math.max(y, skirtFrom);
					key = key(colors.color(world, state, biome, dir), light(topLight, kind), state);
				}
				if (key >= 0) {
					if (key == pendingKey && y0 == pendingY1) {
						pendingY1 = top;
					} else {
						if (pendingKey >= 0) strip(dir, plane, tangent, pendingY0, pendingY1, pendingKey);
						pendingKey = key;
						pendingY0 = y0;
						pendingY1 = top;
					}
				}
			}
			y = top;
			if (rTop == top) i++;
			if (nTop == top) j++;
		}
		if (pendingKey >= 0) strip(dir, plane, tangent, pendingY0, pendingY1, pendingKey);
	}

	/** A face's color, light and whether it's water, as a number the faces carry. */
	private int key(int rgb, int light, int state) {
		long k = (rgb & 0xFFFFFFL) << 8 | light | (long) world.material(state) << 32;
		int id = keyIds.get(k);
		if (id < 0) {
			id = keys.size();
			if (id >= 1 << 20) return 0;
			keys.add(k);
			keyIds.put(k, id);
		}
		return id;
	}

	private void flat(int dir, int y, int z, int x, int key) {
		add(dir, (long) y << 36 | (long) z << 28 | (long) x << 20 | key);
	}

	private void strip(int dir, int plane, int tangent, int y0, int y1, int key) {
		add(dir, (long) plane << 52 | (long) y0 << 40 | (long) y1 << 28 | (long) key << 8 | tangent);
	}

	private void add(int dir, long face) {
		int n = faceCounts[dir];
		if (n == faces[dir].length) faces[dir] = Arrays.copyOf(faces[dir], n * 2);
		faces[dir][n] = face;
		faceCounts[dir] = n + 1;
	}

	/**
	 * Merges the up or down faces of each height: along x into rows, then rows of the same extent and key along z into
	 * rectangles.
	 */
	private void mergeFlat(int dir, long[] f, int n, int chunk) {
		int i = 0;
		while (i < n) {
			int y = (int) (f[i] >>> 36);
			int open = 0, lastZ = -2;
			while (i < n && (int) (f[i] >>> 36) == y) {
				int z = (int) (f[i] >>> 28) & 0xFF;
				int segs = 0;
				while (i < n && (f[i] >>> 28) == ((long) y << 8 | z)) {
					int x0 = (int) (f[i] >>> 20) & 0xFF, key = (int) f[i] & 0xFFFFF, x1 = x0;
					i++;
					while (i < n && (f[i] >>> 28) == ((long) y << 8 | z) && ((int) f[i] & 0xFFFFF) == key
							&& ((int) (f[i] >>> 20) & 0xFF) == x1 + 1 && (chunk == 0 || (x1 + 1) % chunk != 0)) {
						x1++;
						i++;
					}
					sx0[segs] = x0;
					sx1[segs] = x1;
					skey[segs] = key;
					segs++;
				}
				boolean continues = z == lastZ + 1 && (chunk == 0 || z % chunk != 0);
				int p = 0, next = 0;
				for (int s = 0; s < segs; s++) {
					if (continues) {
						while (p < open && ox0[p] < sx0[s]) {
							rect(dir, y, ox0[p], ox1[p], oz0[p], lastZ, okey[p]);
							p++;
						}
						if (p < open && ox0[p] == sx0[s] && ox1[p] == sx1[s] && okey[p] == skey[s]) {
							nx0[next] = sx0[s];
							nx1[next] = sx1[s];
							nz0[next] = oz0[p];
							nkey[next] = skey[s];
							next++;
							p++;
							continue;
						}
					}
					nx0[next] = sx0[s];
					nx1[next] = sx1[s];
					nz0[next] = z;
					nkey[next] = skey[s];
					next++;
				}
				for (int q = continues ? p : 0; q < open; q++) rect(dir, y, ox0[q], ox1[q], oz0[q], lastZ, okey[q]);
				int[] t;
				t = ox0; ox0 = nx0; nx0 = t;
				t = ox1; ox1 = nx1; nx1 = t;
				t = oz0; oz0 = nz0; nz0 = t;
				t = okey; okey = nkey; nkey = t;
				open = next;
				lastZ = z;
			}
			for (int q = 0; q < open; q++) rect(dir, y, ox0[q], ox1[q], oz0[q], lastZ, okey[q]);
		}
	}

	/** Merges side strips of the same height range and key that sit next to each other along the face. */
	private void mergeSides(int dir, long[] f, int n, int chunk) {
		int i = 0;
		while (i < n) {
			long group = f[i] >>> 8;
			int t0 = (int) (f[i] & 0xFF), t1 = t0;
			i++;
			while (i < n && f[i] >>> 8 == group && (int) (f[i] & 0xFF) == t1 + 1 && (chunk == 0 || (t1 + 1) % chunk != 0)) {
				t1++;
				i++;
			}
			int plane = (int) (group >>> 44) & 0xFF;
			int y0 = (int) (group >>> 32) & 0xFFF, y1 = (int) (group >>> 20) & 0xFFF, key = (int) group & 0xFFFFF;
			int a = t0, b = t1 + 1;
			switch (dir) {
				case NORTH -> quad(dir, key, b, y1, plane, b, y0, plane, a, y0, plane, a, y1, plane);
				case SOUTH -> quad(dir, key, a, y1, plane + 1, a, y0, plane + 1, b, y0, plane + 1, b, y1, plane + 1);
				case WEST -> quad(dir, key, plane, y1, a, plane, y0, a, plane, y0, b, plane, y1, b);
				default -> quad(dir, key, plane + 1, y1, b, plane + 1, y0, b, plane + 1, y0, a, plane + 1, y1, a);
			}
		}
	}

	private void rect(int dir, int y, int x0, int x1, int z0, int z1, int key) {
		int ax = x0, bx = x1 + 1, az = z0, bz = z1 + 1;
		if (dir == UP) quad(dir, key, ax, y + 1, az, ax, y + 1, bz, bx, y + 1, bz, bx, y + 1, az);
		else quad(dir, key, ax, y, bz, ax, y, az, bx, y, az, bx, y, bz);
	}

	private void quad(int dir, int key, int x0, int y0, int z0, int x1, int y1, int z1, int x2, int y2, int z2, int x3, int y3, int z3) {
		long k = keys.getLong(key);
		int material = (int) (k >>> 32);
		boolean water = material == LodKinds.WATER_SLOT;
		if (water ? waterCount + 8 > waterOut.length : outCount + 8 > out.length) {
			if (water) waterOut = Arrays.copyOf(waterOut, waterOut.length * 2);
			else out = Arrays.copyOf(out, out.length * 2);
		}
		int light = (int) k & 0xFF, rgb = (int) (k >>> 8) & 0xFFFFFF;
		int color = (rgb >> 16 & 255) | (rgb >> 8 & 255) << 8 | (rgb & 255) << 16 | light << 24;
		vertex(water, dir, material, x0, y0, z0, color);
		vertex(water, dir, material, x1, y1, z1, color);
		vertex(water, dir, material, x2, y2, z2, color);
		vertex(water, dir, material, x3, y3, z3, color);
		minY = Math.min(minY, Math.min(Math.min(y0, y1), Math.min(y2, y3)));
		maxY = Math.max(maxY, Math.max(Math.max(y0, y1), Math.max(y2, y3)));
	}

	private void vertex(boolean water, int dir, int material, int x, int y, int z, int color) {
		int position = ((z * (NODE + 1) + x) * 6 + dir) | material << 17 | y << 20;
		if (water) {
			waterOut[waterCount++] = position;
			waterOut[waterCount++] = color;
		} else {
			out[outCount++] = position;
			out[outCount++] = color;
		}
	}
}
