package com.afterburner.client.render;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;

/**
 * The quads of one chunk section layer, regrouped by the way they face. A quad that faces away from the camera is
 * thrown away by the GPU anyway, but only after its four vertices went through the vertex shader. With the quads
 * grouped, the groups that face away are left out of the draw: every visible run of groups gets a draw of its own in
 * the region's multi-draw, where an extra draw costs next to nothing.
 */
public final class SectionFaces {
	/**
	 * Group ids. The diagonal ones are upright quads at 45 degrees, facing +x+z, -x-z, +x-z and -x+z: the two crossed
	 * planes of plants like grass, flowers, kelp and seagrass, both sides of each. "Any" holds the rest (rotated models).
	 */
	private static final int WEST = 0, EAST = 1, DOWN = 2, UP = 3, NORTH = 4, SOUTH = 5, PP = 6, NN = 7, PN = 8, NP = 9, ANY = 10, GROUPS = 11;
	/** x and z sign of each diagonal group's facing, from {@link #PP}. */
	private static final int[] DIAGONAL_X = {1, -1, 1, -1}, DIAGONAL_Z = {1, -1, -1, 1};
	private static final float FLAT = 1.0E-4F, DIAGONAL_FLAT = 1.0E-3F;
	/** How far behind a group's plane the camera may be while the group still counts as visible. */
	private static final float MARGIN = 0.125F;
	/** The same for the diagonal groups, whose planes are in x + z (or x - z) units, about 1.41 times larger. */
	private static final float DIAGONAL_MARGIN = 0.1875F;
	/** A draw takes in a hidden group of fewer quads than this rather than splitting around it. */
	private static final int MIN_GAP = 16;
	/** {@code -Dafterburner.diagonalFaces=false} puts diagonal quads in "any", to compare. */
	private static final boolean DIAGONALS = !"false".equals(System.getProperty("afterburner.diagonalFaces"));

	/** Quads drawn and quads there were, for the benchmark report. Render thread only. */
	public static long drawnQuads, totalQuads;

	/**
	 * Everything in one array: the group stored at each position in the buffer, then the first quad of the group at each
	 * position ({@code GROUPS + 1} of them, the last is the number of quads), then per group id the plane bits: for a group
	 * facing towards +axis its lowest plane, towards -axis its highest, for a diagonal group its lowest {@code sx * x + sz * z}.
	 */
	private static final int ORDER = 0, START = GROUPS, PLANE = 2 * GROUPS + 1;
	public static final int SIZE = 3 * GROUPS + 1;

	private SectionFaces() {
	}

	/**
	 * Reorders the quads in {@code vertices} by facing, in place, keeping their order within a group (so overlays like
	 * the grass side still draw on top of the block under them). Returns the face groups ({@link #SIZE} ints), or null when
	 * it wouldn't help. Positions must be three floats at the start of each vertex, relative to the section's corner.
	 */
	public static int @Nullable [] sort(ByteBuffer vertices, int vertexCount, int vertexSize) {
		return sort(vertices, vertexCount, vertexSize, null);
	}

	/** The same, writing into {@code moved} (one per quad, if not null) where each quad went when they were reordered. */
	public static int @Nullable [] sort(ByteBuffer vertices, int vertexCount, int vertexSize, int @Nullable [] moved) {
		if (vertexCount % 4 != 0 || vertexCount < 8) return null;
		int quads = vertexCount / 4, quadSize = vertexSize * 4;
		int[] data = new int[SIZE];
		for (int g = 0; g < GROUPS; g++) data[ORDER + g] = g;
		float[] plane = new float[GROUPS];
		for (int g = 0; g < GROUPS; g++) plane[g] = isPositive(g) ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
		byte[] groupOf = new byte[quads];
		int[] count = new int[GROUPS];
		for (int q = 0; q < quads; q++) {
			int g = classify(vertices, q * quadSize, vertexSize, plane);
			groupOf[q] = (byte) g;
			count[g]++;
		}
		if (count[ANY] == quads) return null;
		for (int g = 0; g < GROUPS; g++) data[PLANE + g] = Float.floatToRawIntBits(plane[g]);

		int[] next = new int[GROUPS];
		for (int pos = 0; pos < GROUPS; pos++) {
			int g = data[ORDER + pos];
			next[g] = data[START + pos];
			data[START + pos + 1] = data[START + pos] + count[g];
		}
		byte[] copy = new byte[quads * quadSize];
		vertices.get(0, copy);
		for (int q = 0; q < quads; q++) {
			int to = next[groupOf[q]]++;
			vertices.put(to * quadSize, copy, q * quadSize, quadSize);
			if (moved != null) moved[q] = to;
		}
		return data;
	}

	/** Which group a quad belongs to, also widening that group's plane. */
	private static int classify(ByteBuffer v, int at, int stride, float[] plane) {
		float x0 = v.getFloat(at), y0 = v.getFloat(at + 4), z0 = v.getFloat(at + 8);
		float x1 = v.getFloat(at + stride), y1 = v.getFloat(at + stride + 4), z1 = v.getFloat(at + stride + 8);
		float x2 = v.getFloat(at + 2 * stride), y2 = v.getFloat(at + 2 * stride + 4), z2 = v.getFloat(at + 2 * stride + 8);
		float x3 = v.getFloat(at + 3 * stride), y3 = v.getFloat(at + 3 * stride + 4), z3 = v.getFloat(at + 3 * stride + 8);
		// The front of a quad is the side its vertices go counter-clockwise on, the side of (v1 - v0) x (v2 - v0).
		float nx = (y1 - y0) * (z2 - z0) - (z1 - z0) * (y2 - y0);
		float ny = (z1 - z0) * (x2 - x0) - (x1 - x0) * (z2 - z0);
		float nz = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
		if (nx == 0 && ny == 0 && nz == 0) {
			// First triangle has no area, the second one decides.
			nx = (y2 - y0) * (z3 - z0) - (z2 - z0) * (y3 - y0);
			ny = (z2 - z0) * (x3 - x0) - (x2 - x0) * (z3 - z0);
			nz = (x2 - x0) * (y3 - y0) - (y2 - y0) * (x3 - x0);
		}
		int g;
		float p;
		if (same(x0, x1, x2, x3) && nx != 0) {
			g = nx > 0 ? EAST : WEST;
			p = x0;
		} else if (same(y0, y1, y2, y3) && ny != 0) {
			g = ny > 0 ? UP : DOWN;
			p = y0;
		} else if (same(z0, z1, z2, z3) && nz != 0) {
			g = nz > 0 ? SOUTH : NORTH;
			p = z0;
		} else {
			return diagonal(nx, ny, nz, x0, z0, x1, z1, x2, z2, x3, z3, plane);
		}
		plane[g] = isPositive(g) ? Math.min(plane[g], p) : Math.max(plane[g], p);
		return g;
	}

	/** The diagonal group of an upright quad at 45 degrees, or {@link #ANY}. */
	private static int diagonal(float nx, float ny, float nz, float x0, float z0, float x1, float z1, float x2, float z2, float x3, float z3,
			float[] plane) {
		float ax = Math.abs(nx), az = Math.abs(nz), length = ax + Math.abs(ny) + az;
		if (!DIAGONALS || ax == 0 || az == 0 || Math.abs(ny) > 1.0E-3F * length || Math.abs(ax - az) > 1.0E-3F * length) return ANY;
		int sx = nx > 0 ? 1 : -1, sz = nz > 0 ? 1 : -1;
		float p = sx * x0 + sz * z0;
		if (Math.abs(sx * x1 + sz * z1 - p) > DIAGONAL_FLAT || Math.abs(sx * x2 + sz * z2 - p) > DIAGONAL_FLAT
				|| Math.abs(sx * x3 + sz * z3 - p) > DIAGONAL_FLAT) return ANY;
		int g = sx > 0 ? (sz > 0 ? PP : PN) : (sz > 0 ? NP : NN);
		plane[g] = Math.min(plane[g], p);
		return g;
	}

	private static boolean same(float a, float b, float c, float d) {
		return Math.abs(a - b) < FLAT && Math.abs(a - c) < FLAT && Math.abs(a - d) < FLAT;
	}

	/** Whether the group's plane is its lowest (true) or highest one. */
	private static boolean isPositive(int g) {
		return g == EAST || g == UP || g == SOUTH || g >= PP;
	}

	/** Can a camera at this position (relative to the section's corner) see the front of any quad in the group? */
	private static boolean visible(int[] data, int g, double x, double y, double z) {
		if (g == ANY) return true;
		float plane = Float.intBitsToFloat(data[PLANE + g]);
		return switch (g) {
			case WEST -> x < plane + MARGIN;
			case EAST -> x > plane - MARGIN;
			case DOWN -> y < plane + MARGIN;
			case UP -> y > plane - MARGIN;
			case NORTH -> z < plane + MARGIN;
			case SOUTH -> z > plane - MARGIN;
			default -> DIAGONAL_X[g - PP] * x + DIAGONAL_Z[g - PP] * z > plane - DIAGONAL_MARGIN;
		};
	}

	/** Takes the draws of {@link #addVisible}: a run of quads starting at a vertex. */
	public interface Sink {
		void add(int indexCount, int baseVertex);
	}

	/**
	 * Where the camera (relative as for {@link #addVisible}) may go with the same groups visible: open ranges of x, y, z,
	 * x + z and x - z, each low then high, at {@code box[b]} on. False if no group's visibility depends on where it is.
	 */
	public static boolean holds(int[] data, double x, double y, double z, float[] box, int b) {
		for (int k = 0; k < 10; k += 2) {
			box[b + k] = Float.NEGATIVE_INFINITY;
			box[b + k + 1] = Float.POSITIVE_INFINITY;
		}
		double u = x + z, v = x - z;
		boolean any = false;
		for (int pos = 0; pos < GROUPS; pos++) {
			if (data[START + pos] == data[START + pos + 1]) continue;
			int g = data[ORDER + pos];
			if (g == ANY) continue;
			any = true;
			float plane = Float.intBitsToFloat(data[PLANE + g]);
			// The same thresholds as visible(); the diagonal ones that face the other way are on minus x + z (or x - z).
			switch (g) {
				case WEST -> keep(box, b, x, plane + MARGIN);
				case EAST -> keep(box, b, x, plane - MARGIN);
				case DOWN -> keep(box, b + 2, y, plane + MARGIN);
				case UP -> keep(box, b + 2, y, plane - MARGIN);
				case NORTH -> keep(box, b + 4, z, plane + MARGIN);
				case SOUTH -> keep(box, b + 4, z, plane - MARGIN);
				case PP -> keep(box, b + 6, u, plane - DIAGONAL_MARGIN);
				case NN -> keep(box, b + 6, u, -(plane - DIAGONAL_MARGIN));
				case PN -> keep(box, b + 8, v, plane - DIAGONAL_MARGIN);
				default -> keep(box, b + 8, v, -(plane - DIAGONAL_MARGIN));
			}
		}
		return any;
	}

	/**
	 * Whether the camera is still inside the range {@link #holds} left at {@code box[b]}. Inside means every group's
	 * visibility threshold is on the same side as when the box was made, so the same groups are visible and the box
	 * (and the draw) need not be worked out again. Cheaper than {@link #holds}: no per-group pass.
	 */
	public static boolean holdsAt(float[] box, int b, double x, double y, double z) {
		return x > box[b] && x < box[b + 1] && y > box[b + 2] && y < box[b + 3] && z > box[b + 4] && z < box[b + 5]
				&& x + z > box[b + 6] && x + z < box[b + 7] && x - z > box[b + 8] && x - z < box[b + 9];
	}

	/** Narrows the open range at {@code box[k]} (low) and {@code box[k + 1]} (high) to the side of {@code threshold} that {@code c} is on. */
	private static void keep(float[] box, int k, double c, float threshold) {
		if (c < threshold) {
			box[k + 1] = Math.min(box[k + 1], threshold);
		} else if (c > threshold) {
			box[k] = Math.max(box[k], threshold);
		} else {
			// On it: holds nowhere, so filled again next time.
			box[k] = box[k + 1] = threshold;
		}
	}

	/**
	 * Every visible run of the face groups becomes a draw of its own. A hidden group of only a few quads between two
	 * visible ones is drawn along instead. The camera is relative to the section's corner.
	 */
	public static void addVisible(Sink sink, int[] data, int baseVertex, double x, double y, double z) {
		int quads = data[START + GROUPS];
		int runStart = 0, runEnd = -1, drawn = 0;
		for (int pos = 0; pos < GROUPS; pos++) {
			int s = data[START + pos], e = data[START + pos + 1];
			if (s == e || !visible(data, data[ORDER + pos], x, y, z)) continue;
			if (runEnd < 0) {
				runStart = s;
			} else if (s - runEnd >= MIN_GAP) {
				sink.add((runEnd - runStart) * 6, baseVertex + runStart * 4);
				drawn += runEnd - runStart;
				runStart = s;
			}
			runEnd = e;
		}
		if (runEnd > runStart) {
			sink.add((runEnd - runStart) * 6, baseVertex + runStart * 4);
			drawn += runEnd - runStart;
		}
		drawnQuads += drawn;
		totalQuads += quads;
	}
}
