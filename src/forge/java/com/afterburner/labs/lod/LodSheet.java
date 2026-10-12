package com.afterburner.labs.lod;

import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 16 x 16 columns of the far terrain at one level: a voxel is 2^level blocks wide and tall, so a sheet covers 16 x 16 blocks
 * at level 0 (one chunk) and 256 x 256 at level 4. Each column is a list of runs from the bottom of the world up, each one
 * voxel value ({@code what}) and where it ends. A voxel value is the block's place in the world's palette ({@link LodWorld})
 * shifted up 8 bits, with the sky light (bits 4-7) and block light (bits 0-3) of an air voxel below it.
 * <p>
 * Only the worker thread ({@link LodWorker}) uses sheets.
 */
final class LodSheet {
	static final int SIZE = 16, COLUMNS = SIZE * SIZE;
	/** Palette places with a fixed meaning: air (and plants, thin blocks), and solid blocks no open air touches. */
	static final int AIR = 0, HIDDEN = 1;
	private static final int FORMAT = 1;

	/** Per column (z * 16 + x): the runs, each {@link #run}; null where nothing was seen. */
	final long[] @Nullable [] columns = new long[COLUMNS][];
	/**
	 * Per column: the biome's place in the world's biome list (bits 0-23), and for land made from the seed rather than
	 * seen, the {@link com.afterburner.labs.worldgen.FarLand#VERSION} that made it (bits 24-31; 0 for land seen).
	 */
	final int[] biomes = new int[COLUMNS];
	static final int BIOME = 0xFFFFFF, MADE_SHIFT = 24;

	static long run(int what, int top) {
		return (long) what << 16 | top;
	}

	static int what(long run) {
		return (int) (run >>> 16);
	}

	/** Where the run ends (exclusive), in voxels from the bottom of the world. */
	static int top(long run) {
		return (int) (run & 0xFFFF);
	}

	static int state(int what) {
		return what >>> 8;
	}

	static int sky(int what) {
		return what >> 4 & 15;
	}

	static int blockLight(int what) {
		return what & 15;
	}

	static int air(int sky, int block) {
		return sky << 4 | block;
	}

	/**
	 * Whether this was made from a chunk copied without its light: air in full sky light (15) under 8 or more voxels of
	 * rock no open air touches, in 16 or more columns. The light engine never gives that (sky light under rock is less than
	 * 15), but a chunk with no light yet reads as full sky light everywhere, caves too. (Under a tree's leaves there is
	 * full sky light, but their hidden middle is thinner.)
	 */
	boolean lightMissing() {
		int found = 0;
		for (long[] runs : columns) {
			if (runs == null) continue;
			boolean rockAbove = false;
			for (int r = runs.length - 1; r >= 0; r--) {
				int what = what(runs[r]);
				if (state(what) == HIDDEN) {
					if (top(runs[r]) - (r > 0 ? top(runs[r - 1]) : 0) >= 8) rockAbove = true;
				} else if (rockAbove && state(what) == AIR && sky(what) == 15) {
					if (++found >= 16) return true;
					break;
				}
			}
		}
		return false;
	}

	boolean isEmpty() {
		for (long[] c : columns) if (c != null) return false;
		return true;
	}

	/** Whether every column has something. */
	boolean isFull() {
		for (long[] c : columns) if (c == null) return false;
		return true;
	}

	/** Lets go of columns made from the seed by another version than {@code version}; true if there were any. */
	boolean dropMadeBefore(int version) {
		boolean any = false;
		for (int i = 0; i < COLUMNS; i++) {
			int made = biomes[i] >>> MADE_SHIFT;
			if (columns[i] != null && made != 0 && made != version) {
				columns[i] = null;
				biomes[i] = 0;
				any = true;
			}
		}
		return any;
	}

	// ---- Storage: varints, deflated. Worker thread only. ----

	private static final Deflater DEFLATER = new Deflater(3);
	private static final Inflater INFLATER = new Inflater();
	private static byte[] scratch = new byte[1 << 16];
	private static byte[] packed = new byte[1 << 14];

	byte[] encode() {
		int n = 0;
		n = varint(FORMAT, n);
		for (int i = 0; i < COLUMNS; i++) {
			long[] runs = columns[i];
			if (runs == null) {
				n = varint(0, n);
				continue;
			}
			n = varint(runs.length, n);
			n = varint(biomes[i], n);
			int bottom = 0;
			for (long r : runs) {
				n = varint(what(r), n);
				n = varint(top(r) - bottom, n);
				bottom = top(r);
			}
		}
		DEFLATER.reset();
		DEFLATER.setInput(scratch, 0, n);
		DEFLATER.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream(n / 3 + 16);
		while (!DEFLATER.finished()) {
			int got = DEFLATER.deflate(packed);
			out.write(packed, 0, got);
		}
		return out.toByteArray();
	}

	/** Null if the bytes aren't a sheet (a damaged file). */
	static @Nullable LodSheet decode(byte[] bytes) {
		INFLATER.reset();
		INFLATER.setInput(bytes);
		int n = 0;
		try {
			while (!INFLATER.finished()) {
				if (n == scratch.length) scratch = Arrays.copyOf(scratch, scratch.length * 2);
				int got = INFLATER.inflate(scratch, n, scratch.length - n);
				if (got == 0 && (INFLATER.needsInput() || INFLATER.needsDictionary())) break;
				n += got;
			}
		} catch (DataFormatException e) {
			return null;
		}
		Reader in = new Reader(scratch, n);
		try {
			if (in.next() != FORMAT) return null;
			LodSheet sheet = new LodSheet();
			for (int i = 0; i < COLUMNS; i++) {
				int count = in.next();
				if (count == 0) continue;
				sheet.biomes[i] = in.next();
				long[] runs = new long[count];
				int top = 0;
				for (int r = 0; r < count; r++) {
					int what = in.next();
					top += in.next();
					runs[r] = run(what, top);
				}
				sheet.columns[i] = runs;
			}
			return sheet;
		} catch (ArrayIndexOutOfBoundsException e) {
			return null;
		}
	}

	private static int varint(int value, int n) {
		if (n + 5 > scratch.length) scratch = Arrays.copyOf(scratch, scratch.length * 2);
		while ((value & ~0x7F) != 0) {
			scratch[n++] = (byte) (value & 0x7F | 0x80);
			value >>>= 7;
		}
		scratch[n++] = (byte) value;
		return n;
	}

	private static final class Reader {
		private final byte[] data;
		private final int end;
		private int at;

		Reader(byte[] data, int end) {
			this.data = data;
			this.end = end;
		}

		int next() {
			int value = 0;
			for (int shift = 0; ; shift += 7) {
				if (at >= end) throw new ArrayIndexOutOfBoundsException();
				byte b = data[at++];
				value |= (b & 0x7F) << shift;
				if (b >= 0) return value;
			}
		}
	}
}
