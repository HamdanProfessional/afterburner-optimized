package com.afterburner.labs.lod;

import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/**
 * A copy of one chunk's blocks, light and surface biomes, taken on the game thread as the chunk leaves, and turned into a
 * level 0 sheet on the worker ({@link #toSheet}).
 */
final class LodSnapshot {
	private static final DataLayer FULL_SKY = new DataLayer(15), DARK = new DataLayer(0);
	private static final Identifier PLAINS = Identifier.withDefaultNamespace("plains");

	final LodWorld world;
	final int cx, cz;
	/** Per section from the bottom: the blocks, null if only air. */
	private final @Nullable PalettedContainer<BlockState>[] blocks;
	/** Per section: sky light, null where the light engine has none (filled in from above in {@link #toSheet}). */
	private final @Nullable DataLayer[] sky;
	/** Per section: block light, null for none. */
	private final @Nullable DataLayer[] blockLight;
	/** Per 4 x 4 columns (z * 4 + x): the biome at the surface. */
	private final Identifier[] biomes = new Identifier[16];
	/** Roughly the memory the copy takes, for {@link LodWorker}'s limit. */
	final long bytes;

	@SuppressWarnings("unchecked")
	LodSnapshot(LodWorld world, ClientLevel level, LevelChunk chunk) {
		this.world = world;
		this.cx = chunk.getPos().x();
		this.cz = chunk.getPos().z();
		LevelChunkSection[] sections = chunk.getSections();
		int n = sections.length;
		blocks = new PalettedContainer[n];
		sky = new DataLayer[n];
		blockLight = new DataLayer[n];
		LevelLightEngine light = level.getLightEngine();
		int minSection = chunk.getMinSectionY();
		long size = 256;
		for (int i = 0; i < n; i++) {
			LevelChunkSection section = sections[i];
			if (section != null && !section.hasOnlyAir()) {
				blocks[i] = section.getStates().copy();
				size += 4096;
			}
			SectionPos pos = SectionPos.of(cx, minSection + i, cz);
			if (world.skyLight) {
				DataLayer layer = light.getLayerListener(LightLayer.SKY).getDataLayerData(pos);
				if (layer != null) {
					if (layer.isDefinitelyFilledWith(15)) {
						sky[i] = FULL_SKY;
					} else if (layer.isDefinitelyFilledWith(0)) {
						sky[i] = DARK;
					} else {
						sky[i] = layer.copy();
						size += 2048;
					}
				}
			}
			DataLayer layer = light.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
			if (layer != null && !layer.isDefinitelyFilledWith(0)) {
				blockLight[i] = layer.copy();
				size += 2048;
			}
		}
		for (int qz = 0; qz < 4; qz++) {
			for (int qx = 0; qx < 4; qx++) {
				int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, qx * 4 + 2, qz * 4 + 2);
				Holder<Biome> biome = chunk.getNoiseBiome(qx, y >> 2, qz);
				biomes[qz * 4 + qx] = biome.unwrapKey().map(k -> k.identifier()).orElse(PLAINS);
			}
		}
		bytes = size;
	}

	// ---- On the worker ----

	private static byte[] kinds = new byte[0];
	private static int[] cells = new int[0];
	private static boolean[] open = new boolean[0];
	/** Per section: its one cell when it's all open air in the same light, else -1. */
	private static int[] uniform = new int[0];
	/** False: those sections too voxel by voxel, for {@link LodWorker}'s check that the shortcut changes nothing. */
	static boolean airShortcut = true;
	/** Per column (z * 16 + x): its lowest open voxel, or the height if none. */
	private static final int[] lowestOpen = new int[256];
	/** Per column: the sky light of a section the light engine has none for ({@link #skyFromAbove}). */
	private static final byte[] skyAbove = new byte[256];
	/** Per column: one above its highest voxel that isn't open air, 0 if none. */
	private static final int[] surfaces = new int[256];

	/**
	 * The chunk as a level 0 sheet. A solid voxel that no open air touches (open: air the sky lights, or any air where there's
	 * no sky) becomes {@link LodSheet#HIDDEN}, and so does dark air: both can't be seen from far away, and the columns below
	 * the surface shrink to one run.
	 */
	LodSheet toSheet() {
		int height = world.height;
		int sections = blocks.length;
		int voxels = 256 * height;
		if (kinds.length < voxels) {
			kinds = new byte[voxels];
			cells = new int[voxels];
			open = new boolean[voxels];
		}
		if (uniform.length < sections) uniform = new int[sections];
		boolean skyDim = world.skyLight;
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState last = null;
		int lastCell = 0;
		byte lastKind = LodKinds.EMPTY;
		java.util.Arrays.fill(surfaces, 0);
		// Index: (y * 16 + z) * 16 + x.
		for (int s = 0; s < sections; s++) {
			PalettedContainer<BlockState> states = blocks[s];
			DataLayer skyLayer = skyDim ? sky[s] : null;
			DataLayer blockLayer = blockLight[s];
			// The same for every layer of the section: worked out once, not per voxel.
			boolean fullSky = skyLayer == FULL_SKY;
			if (skyDim && skyLayer == null) fullSky = skyFromAbove(s);
			uniform[s] = -1;
			if (airShortcut && states == null && blockLayer == null && (!skyDim || fullSky)) {
				// All air in one light (most of the sky): the whole section at once.
				int from = s * 4096, to = Math.min(height, s * 16 + 16) * 256;
				int cell = LodSheet.air(skyDim ? 15 : 0, 0);
				java.util.Arrays.fill(kinds, from, to, LodKinds.EMPTY);
				java.util.Arrays.fill(cells, from, to, cell);
				java.util.Arrays.fill(open, from, to, true);
				uniform[s] = cell;
				continue;
			}
			for (int ly = 0; ly < 16; ly++) {
				int y = s * 16 + ly;
				if (y >= height) break;
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						int i = (y * 16 + z) * 16 + x;
						BlockState state = states == null ? air : states.get(x, ly, z);
						if (state != last) {
							last = state;
							int cell = world.cell(state);
							lastKind = (byte) cell;
							lastCell = cell & ~0xFF;
						}
						kinds[i] = lastKind;
						if (lastKind == LodKinds.EMPTY) {
							int skyLight = !skyDim ? 0 : skyLayer != null ? skyLayer.get(x, ly, z) : skyAbove[z * 16 + x];
							int block = blockLayer != null ? blockLayer.get(x, ly, z) : 0;
							cells[i] = LodSheet.air(skyLight, block);
							boolean o = !skyDim || skyLight > 0;
							open[i] = o;
							if (!o) surfaces[z * 16 + x] = y + 1;
						} else {
							cells[i] = lastCell;
							open[i] = false;
							surfaces[z * 16 + x] = y + 1;
						}
					}
				}
			}
		}
		// Neighbor chunks' edges, where they were seen.
		LodSheet west = world.get(0, cx - 1, cz), east = world.get(0, cx + 1, cz);
		LodSheet north = world.get(0, cx, cz - 1), south = world.get(0, cx, cz + 1);
		findLowestOpen(height);
		LodSheet sheet = new LodSheet();
		long[] runs = new long[64];
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int surface = surfaces[z * 16 + x];
				int closed = closedBelow(x, z, height, surface, west, east, north, south);
				int count = 0;
				int runWhat = -1, runTop = 0;
				for (int y = 0; y < height; y++) {
					int u = (y & 15) == 0 ? uniform[y >> 4] : -1;
					if (u >= 0) {
						// A section of open air in one light: one stretch, never hidden.
						if (u != runWhat) {
							if (runWhat >= 0) {
								if (count == runs.length) runs = java.util.Arrays.copyOf(runs, count * 2);
								runs[count++] = LodSheet.run(runWhat, y);
							}
							runWhat = u;
						}
						y = Math.min(height, y + 16) - 1;
						runTop = y + 1;
						continue;
					}
					int i = (y * 16 + z) * 16 + x;
					int what = cells[i];
					byte kind = kinds[i];
					if (kind == LodKinds.EMPTY ? !open[i]
							: kind != LodKinds.WATER && (y < closed || !touchesOpen(x, y, z, height, surface, west, east, north, south))) {
						what = LodSheet.HIDDEN << 8;
					}
					if (what != runWhat) {
						if (runWhat >= 0) {
							if (count == runs.length) runs = java.util.Arrays.copyOf(runs, count * 2);
							runs[count++] = LodSheet.run(runWhat, y);
						}
						runWhat = what;
					}
					runTop = y + 1;
				}
				if (count == runs.length) runs = java.util.Arrays.copyOf(runs, count * 2);
				runs[count++] = LodSheet.run(runWhat, runTop);
				sheet.columns[z * 16 + x] = java.util.Arrays.copyOf(runs, count);
				Identifier biome = biomes[(z >> 2) * 4 + (x >> 2)];
				sheet.biomes[z * 16 + x] = world.biomeId(biome);
			}
		}
		return sheet;
	}

	private static void findLowestOpen(int height) {
		for (int c = 0; c < 256; c++) {
			int y = 0;
			while (y < height && !open[y * 256 + c]) y++;
			lowestOpen[c] = y;
		}
	}

	/**
	 * Below this, no voxel of the column touches open air ({@link #touchesOpen} is false): what's open next to it, above
	 * it or below it starts higher up. Most of a column is under the ground, so this saves asking voxel by voxel.
	 */
	private static int closedBelow(int x, int z, int height, int surface,
			@Nullable LodSheet west, @Nullable LodSheet east, @Nullable LodSheet north, @Nullable LodSheet south) {
		int c = z * 16 + x;
		// Open just above (or the top of the world) at y + 1, just below at y - 1: both from y = lowest - 1 up.
		int closed = Math.min(height - 1, lowestOpen[c] - 1);
		closed = Math.min(closed, x > 0 ? lowestOpen[c - 1] : lowestOpenOutside(west, 15, z, surface));
		closed = Math.min(closed, x < 15 ? lowestOpen[c + 1] : lowestOpenOutside(east, 0, z, surface));
		closed = Math.min(closed, z > 0 ? lowestOpen[c - 16] : lowestOpenOutside(north, x, 15, surface));
		return Math.min(closed, z < 15 ? lowestOpen[c + 16] : lowestOpenOutside(south, x, 0, surface));
	}

	/** The lowest y {@link #outsideOpen} is true for. */
	private static int lowestOpenOutside(@Nullable LodSheet neighbor, int x, int z, int surface) {
		long[] runs = neighbor == null ? null : neighbor.columns[z * 16 + x];
		if (runs == null) return Math.max(0, surface - 16);
		int bottom = 0;
		for (long r : runs) {
			int top = LodSheet.top(r);
			if (top > bottom && LodSheet.state(LodSheet.what(r)) == LodSheet.AIR) return bottom;
			bottom = Math.max(bottom, top);
		}
		return bottom;
	}

	private static boolean touchesOpen(int x, int y, int z, int height, int surface,
			@Nullable LodSheet west, @Nullable LodSheet east, @Nullable LodSheet north, @Nullable LodSheet south) {
		if (y + 1 >= height || open[((y + 1) * 16 + z) * 16 + x]) return true;
		if (y > 0 && open[((y - 1) * 16 + z) * 16 + x]) return true;
		if (x > 0 ? open[(y * 16 + z) * 16 + x - 1] : outsideOpen(west, 15, z, y, surface)) return true;
		if (x < 15 ? open[(y * 16 + z) * 16 + x + 1] : outsideOpen(east, 0, z, y, surface)) return true;
		if (z > 0 ? open[(y * 16 + z - 1) * 16 + x] : outsideOpen(north, x, 15, y, surface)) return true;
		return z < 15 ? open[(y * 16 + z + 1) * 16 + x] : outsideOpen(south, x, 0, y, surface);
	}

	/**
	 * Whether the voxel across the chunk's edge is open air: from the neighbor's sheet if it was seen, else guessed as open
	 * down to 16 blocks below this column's surface (so a cliff along the edge keeps its real blocks).
	 */
	private static boolean outsideOpen(@Nullable LodSheet neighbor, int x, int z, int y, int surface) {
		if (neighbor == null) return y >= surface - 16;
		long[] runs = neighbor.columns[z * 16 + x];
		if (runs == null) return y >= surface - 16;
		for (long r : runs) {
			if (y < LodSheet.top(r)) {
				int what = LodSheet.what(r);
				return LodSheet.state(what) == LodSheet.AIR;
			}
		}
		return true;
	}

	/**
	 * Like the light engine for a section it has no light for: the bottom of the next section up that has some, or 15. Into
	 * {@link #skyAbove}; true if that's 15 everywhere.
	 */
	private boolean skyFromAbove(int s) {
		DataLayer layer = null;
		for (int up = s + 1; up < sky.length && layer == null; up++) layer = sky[up];
		boolean full = true;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int light = layer == null ? 15 : layer.get(x, 0, z);
				skyAbove[z * 16 + x] = (byte) light;
				full &= light == 15;
			}
		}
		return full;
	}
}
