package com.afterburner.client.render;

import com.afterburner.Features;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Leaves out of each translucent section draw (water, glass, ice) the faces that point away from the camera.
 * <p>
 * When a section's translucent quads are sorted, the flat faces that point away from the camera's side of the section
 * (on an axis where the camera is outside it) go after all the others, and the draw stops before them for as long as
 * the camera stays on that side. The graphics card drops such faces anyway (translucent terrain culls back faces), but
 * only after running their vertices, and still water has a second, upside-down top face for being seen from below:
 * open water costs twice the vertices otherwise.
 */
public final class TranslucentCulling {
	public static final boolean ENABLED = Features.TRANSLUCENT_CULLING.enabled() && Features.CHUNK_BATCHING.enabled();
	/** Faces that may be left out: flat, pointing +X, -X, +Y, -Y, +Z or -Z, and within their section on that axis. */
	private static final byte PX = 0, NX = 1, PY = 2, NY = 3, PZ = 4, NZ = 5, NONE = 6;
	/** Point of view with nothing left out (draw everything). */
	public static final int NO_PARTITION = -1;

	/** Translucent quads drawn and left out, for the benchmark report. Render thread only. */
	public static long drawnQuads, totalQuads;

	/** Per worker thread: the last index buffer built with a partition, until the chunk buffers take it (address, bytes, front indices, view). */
	private static final ThreadLocal<long[]> LAST = ThreadLocal.withInitial(() -> new long[4]);

	private TranslucentCulling() {
	}

	/** The facing of every quad, kept with the quad centers a section's translucent layer is sorted by. */
	public interface Facings {
		byte @Nullable [] afterburner$facings();

		void afterburner$setFacings(byte[] facings);
	}

	/** On a section mesh: how many indices of its uploaded translucent index buffer face the camera, and from where. */
	public interface Partitioned {
		int afterburner$frontIndices();

		int afterburner$pointOfView();

		void afterburner$setPartition(int frontIndices, int pointOfView);
	}

	/** Vanilla's sorting by distance to the camera, which also tells where the camera is relative to the section's corner. */
	public record RelativeSorting(VertexSorting inner, float x, float y, float z) implements VertexSorting {
		@Override
		public int[] sort(CompactVectorArray points) {
			return inner.sort(points);
		}
	}

	/** Facing of each quad (4 vertices each) of a section's translucent mesh, positions relative to the section's corner. */
	public static byte @Nullable [] facings(ByteBuffer vertices, int vertexCount, VertexFormat format) {
		VertexFormatElement position = format.getElement("Position");
		if (position == null) return null;
		int stride = format.getVertexSize();
		int base = vertices.position() + position.offset();
		int quads = vertexCount / 4;
		byte[] facings = new byte[quads];
		for (int q = 0; q < quads; q++) {
			int a = base + q * 4 * stride, b = a + stride, c = b + stride, d = c + stride;
			float x0 = vertices.getFloat(a), y0 = vertices.getFloat(a + 4), z0 = vertices.getFloat(a + 8);
			float x1 = vertices.getFloat(b), y1 = vertices.getFloat(b + 4), z1 = vertices.getFloat(b + 8);
			float x2 = vertices.getFloat(c), y2 = vertices.getFloat(c + 4), z2 = vertices.getFloat(c + 8);
			float x3 = vertices.getFloat(d), y3 = vertices.getFloat(d + 4), z3 = vertices.getFloat(d + 8);
			// The front side, as the graphics card sees it (counter-clockwise): (v2 - v0) x (v3 - v1).
			float ax = x2 - x0, ay = y2 - y0, az = z2 - z0, bx = x3 - x1, by = y3 - y1, bz = z3 - z1;
			float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
			byte facing = NONE;
			if (x0 == x1 && x0 == x2 && x0 == x3 && nx != 0) {
				facing = nx > 0 ? x0 >= 0 ? PX : NONE : x0 <= 16 ? NX : NONE;
			} else if (y0 == y1 && y0 == y2 && y0 == y3 && ny != 0) {
				facing = ny > 0 ? y0 >= 0 ? PY : NONE : y0 <= 16 ? NY : NONE;
			} else if (z0 == z1 && z0 == z2 && z0 == z3 && nz != 0) {
				facing = nz > 0 ? z0 >= 0 ? PZ : NONE : z0 <= 16 ? NZ : NONE;
			}
			facings[q] = facing;
		}
		return facings;
	}

	/**
	 * Vanilla's sorted index buffer with the faces turned away from the camera moved to the end, in the same order.
	 * Remembers the split for {@link #take}.
	 */
	public static ByteBufferBuilder.@Nullable Result build(MeshData.SortState state, ByteBufferBuilder target, RelativeSorting sorting, byte[] facings) {
		int[] order = sorting.sort(state.centroids());
		int px = side(sorting.x()), py = side(sorting.y()), pz = side(sorting.z());
		// A face pointing +X can't be seen from below the section's lowest X (if it isn't lower), and so on.
		int away = (px < 0 ? 1 << PX : px > 0 ? 1 << NX : 0) | (py < 0 ? 1 << PY : py > 0 ? 1 << NY : 0) | (pz < 0 ? 1 << PZ : pz > 0 ? 1 << NZ : 0);
		IndexType type = state.indexType();
		long at = target.reserve(order.length * 6 * type.bytes);
		int front = 0;
		for (int pass = 0; pass < 2; pass++) {
			for (int q : order) {
				boolean back = (away >> facings[q] & 1) != 0;
				if (back != (pass == 1)) continue;
				if (!back) front++;
				int v = q * 4;
				if (type == IndexType.SHORT) {
					MemoryUtil.memPutShort(at, (short) v);
					MemoryUtil.memPutShort(at + 2, (short) (v + 1));
					MemoryUtil.memPutShort(at + 4, (short) (v + 2));
					MemoryUtil.memPutShort(at + 6, (short) (v + 2));
					MemoryUtil.memPutShort(at + 8, (short) (v + 3));
					MemoryUtil.memPutShort(at + 10, (short) v);
					at += 12;
				} else {
					MemoryUtil.memPutInt(at, v);
					MemoryUtil.memPutInt(at + 4, v + 1);
					MemoryUtil.memPutInt(at + 8, v + 2);
					MemoryUtil.memPutInt(at + 12, v + 2);
					MemoryUtil.memPutInt(at + 16, v + 3);
					MemoryUtil.memPutInt(at + 20, v);
					at += 24;
				}
			}
		}
		ByteBufferBuilder.Result result = target.build();
		long[] last = LAST.get();
		if (result == null) {
			last[0] = 0;
		} else {
			ByteBuffer bytes = result.byteBuffer();
			last[0] = MemoryUtil.memAddress(bytes);
			last[1] = bytes.remaining();
			last[2] = front * 6L;
			last[3] = pointOfView(px, py, pz);
		}
		return result;
	}

	/** A sorted index buffer was built without a split: forget the last one. */
	public static void forget() {
		LAST.get()[0] = 0;
	}

	/** The split of the index buffer about to be uploaded, if it's the one {@link #build} made last on this thread. */
	public static long take(ByteBuffer indices) {
		long[] last = LAST.get();
		if (last[0] == 0 || last[0] != MemoryUtil.memAddress(indices) || last[1] != indices.remaining()) return -1;
		return last[2] << 32 | last[3];
	}

	/** -1, 0 or 1: the camera is below, within or above the section on this axis (from the camera relative to its corner). */
	private static int side(float relative) {
		return Math.clamp(Math.floorDiv((int) Math.floor(relative), 16), -1, 1);
	}

	private static int pointOfView(int px, int py, int pz) {
		return px + 1 | py + 1 << 2 | pz + 1 << 4;
	}

	/** The point of view of a camera at block (x, y, z) on a section with its corner at (ox, oy, oz). */
	public static int pointOfView(int x, int y, int z, int ox, int oy, int oz) {
		return pointOfView(Math.clamp(Math.floorDiv(x - ox, 16), -1, 1), Math.clamp(Math.floorDiv(y - oy, 16), -1, 1), Math.clamp(Math.floorDiv(z - oz, 16), -1, 1));
	}

	/** Whether faces left out for the point of view the index buffer was sorted at are still turned away from the current one. */
	public static boolean holds(int sorted, int current) {
		for (int shift = 0; shift < 6; shift += 2) {
			int s = sorted >> shift & 3;
			if (s != 1 && s != (current >> shift & 3)) return false;
		}
		return true;
	}
}
