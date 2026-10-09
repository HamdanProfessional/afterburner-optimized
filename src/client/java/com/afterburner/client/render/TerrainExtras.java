package com.afterburner.client.render;

import com.afterburner.Features;
import com.afterburner.client.mixin.render.BufferBuilderAccessor;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

/**
 * What a shader pack needs to know about terrain (for the shader pack loader in src/labs, not released yet) that the game's chunk vertices don't say: which way each face points, its
 * texture's middle, and which block it is (the pack's IDs from block.properties). While a pack is on, chunks are built in
 * {@link CompactVertices#EXTENDED_FORMAT}, which has room for all of it in the vanilla vertex's 28 bytes.
 * <p>
 * The block of every vertex is noted while a section is built ({@link Recorder}): which blocks made which vertices of each
 * layer, as runs. The rest comes from the finished quads when they're uploaded.
 */
public final class TerrainExtras {
	/** Only the chunk batcher can draw the extended format. */
	public static final boolean ENABLED = Features.CHUNK_BATCHING.enabled();
	/** A run's value: the block's ID + 1 in the low 16 bits (0 for none), this flag for fluids. */
	public static final int FLUID = 1 << 16;
	private static final int[] NO_RUNS = new int[0];

	/** The pack's ID + 1 of every block state (by its id in Block.BLOCK_STATE_REGISTRY); null while no pack wants terrain built for it. */
	private static volatile int @Nullable [] ids;
	/** Whether sections were ever built in the extended format (some may still be). */
	private static volatile boolean used;
	private static volatile @Nullable Sprites sprites;
	private static @Nullable Collection<TextureAtlasSprite> atlasSprites;
	private static int atlasWidth, atlasHeight;

	private TerrainExtras() {
	}

	/** Whether sections are built in the extended format now. */
	public static boolean active() {
		return ids != null;
	}

	/** Whether sections may be in the extended format: once they have been, until the game closes. */
	public static boolean used() {
		return used;
	}

	/**
	 * Every frame (render thread): the block IDs of the pack drawing the world, or null for none. Every section is built again
	 * when this changes.
	 */
	public static void use(int @Nullable [] table) {
		if (!ENABLED || table == ids) return;
		if (table != null) used = true;
		ids = table;
		Minecraft.getInstance().levelExtractor.allChanged();
	}

	// ---- While a section is built ----

	/** Notes which block made which vertices, for one section build (one thread). */
	public static final class Recorder {
		private final int[] table;
		/** Per layer: start vertex and value, in pairs. */
		private final int[][] runs = new int[ChunkSectionLayer.values().length][];
		private final int[] size = new int[runs.length];
		private int current;
		private @Nullable BlockState lastState;
		private int lastValue;

		private Recorder(int[] table) {
			this.table = table;
		}

		/** A recorder for a section build starting now, or null when sections aren't built for a pack. */
		public static @Nullable Recorder start() {
			int[] table = ids;
			return table == null ? null : new Recorder(table);
		}

		/** The block at the next position is about to be drawn. */
		public void block(Map<ChunkSectionLayer, BufferBuilder> layers, BlockState state) {
			if (state != lastState) {
				lastState = state;
				int at = Block.BLOCK_STATE_REGISTRY.getId(state);
				lastValue = at >= 0 && at < table.length ? table[at] : 0;
			}
			change(layers, lastValue);
		}

		/** Its fluid is about to be drawn: under the fluid's block (water, lava). */
		public void fluid(Map<ChunkSectionLayer, BufferBuilder> layers, FluidState fluid) {
			BlockState state = fluid.createLegacyBlock();
			int at = Block.BLOCK_STATE_REGISTRY.getId(state);
			change(layers, (at >= 0 && at < table.length ? table[at] : 0) | FLUID);
		}

		private void change(Map<ChunkSectionLayer, BufferBuilder> layers, int value) {
			if (value == current) return;
			for (Map.Entry<ChunkSectionLayer, BufferBuilder> e : layers.entrySet()) {
				int layer = e.getKey().ordinal();
				int n = ((BufferBuilderAccessor) e.getValue()).afterburner$vertices();
				// What the layer has so far was drawn under the current value.
				if (size[layer] == 0 && n > 0) add(layer, 0, current);
				add(layer, n, value);
			}
			current = value;
		}

		private void add(int layer, int start, int value) {
			int[] r = runs[layer];
			int s = size[layer];
			if (s > 0 && r[s - 1] == value) return;
			if (s > 0 && r[s - 2] == start) {
				// Nothing was drawn under the last one.
				r[s - 1] = value;
				if (s > 2 && r[s - 3] == value) size[layer] -= 2;
				return;
			}
			if (r == null) runs[layer] = r = new int[16];
			else if (s == r.length) runs[layer] = r = Arrays.copyOf(r, s * 2);
			r[s] = start;
			r[s + 1] = value;
			size[layer] = s + 2;
		}

		/** The runs of each built layer, by layer ordinal (layers that were never started are null). */
		public int @Nullable [] @Nullable [] finish(Map<ChunkSectionLayer, MeshData> built) {
			int[][] out = new int[runs.length][];
			for (ChunkSectionLayer layer : built.keySet()) {
				int l = layer.ordinal();
				if (size[l] == 0) add(l, 0, current);
				out[l] = size[l] == 0 ? NO_RUNS : Arrays.copyOf(runs[l], size[l]);
			}
			return out;
		}
	}

	/**
	 * The runs of a layer whose quads were reordered (grouped by facing, {@link SectionFaces#sort}): {@code moved[q]} is where
	 * quad q went. Its block goes with it.
	 */
	public static int[] moveRuns(int[] runs, int[] moved) {
		int quads = moved.length;
		int[] values = new int[quads];
		int run = 0, value = 0;
		for (int q = 0; q < quads; q++) {
			while (run < runs.length && runs[run] <= q * 4) {
				value = runs[run + 1];
				run += 2;
			}
			values[moved[q]] = value;
		}
		int[] out = new int[16];
		int n = 0;
		for (int q = 0; q < quads; q++) {
			if (n > 0 && out[n - 1] == values[q]) continue;
			if (n == out.length) out = Arrays.copyOf(out, n * 2);
			out[n] = q * 4;
			out[n + 1] = values[q];
			n += 2;
		}
		return Arrays.copyOf(out, n);
	}

	/** On section build results and compiled section meshes: the runs of each layer, until the layer is uploaded. */
	public interface Holder {
		/** Null if the section wasn't built for a pack. */
		int @Nullable [] @Nullable [] afterburner$blockRuns();

		void afterburner$setBlockRuns(int @Nullable [] @Nullable [] runs);
	}

	// ---- Texture middles ----

	/** The block atlas was (re)made: where its textures are now. Their grid is made when first needed. */
	public static synchronized void atlasUploaded(Collection<TextureAtlasSprite> all, int width, int height) {
		atlasSprites = all;
		atlasWidth = width;
		atlasHeight = height;
		sprites = null;
	}

	/** Writes into {@code out} the middle of the block texture at u, v (atlas coordinates); false if there's none there. */
	public static boolean spriteCenter(float u, float v, float[] out) {
		Sprites s = sprites;
		if (s == null) s = makeSprites();
		if (s == null) return false;
		int i = s.find(u, v);
		if (i < 0) return false;
		out[0] = (s.rects[i * 4] + s.rects[i * 4 + 2]) * 0.5F;
		out[1] = (s.rects[i * 4 + 1] + s.rects[i * 4 + 3]) * 0.5F;
		return true;
	}

	private static synchronized @Nullable Sprites makeSprites() {
		if (sprites == null && atlasSprites != null && atlasWidth > 0 && atlasHeight > 0) sprites = new Sprites(atlasSprites, atlasWidth, atlasHeight);
		return sprites;
	}

	/** Where every texture of the block atlas is: a grid of cells, each listing the textures that reach into it. */
	private static final class Sprites {
		/** u0, v0, u1, v1 of each. */
		final float[] rects;
		final int width, height, shift, cellsX, cellsY;
		/** The textures of cell c are items[start[c] .. start[c + 1]). */
		final int[] start, items;

		Sprites(Collection<TextureAtlasSprite> all, int width, int height) {
			this.width = width;
			this.height = height;
			// Cells of a power of two pixels across, about a quarter of a million at most.
			int shift = 0;
			while ((long) ((width - 1 >> shift) + 1) * ((height - 1 >> shift) + 1) > 1 << 18) shift++;
			this.shift = shift;
			cellsX = (width - 1 >> shift) + 1;
			cellsY = (height - 1 >> shift) + 1;
			TextureAtlasSprite[] list = all.toArray(new TextureAtlasSprite[0]);
			rects = new float[list.length * 4];
			int[] cellRange = new int[list.length * 4];
			int[] count = new int[cellsX * cellsY + 1];
			for (int i = 0; i < list.length; i++) {
				TextureAtlasSprite sprite = list[i];
				rects[i * 4] = sprite.getU0();
				rects[i * 4 + 1] = sprite.getV0();
				rects[i * 4 + 2] = sprite.getU1();
				rects[i * 4 + 3] = sprite.getV1();
				int x0 = Math.max(0, Math.round(sprite.getU0() * width) >> shift), x1 = Math.min(cellsX - 1, Math.round(sprite.getU1() * width) - 1 >> shift);
				int y0 = Math.max(0, Math.round(sprite.getV0() * height) >> shift), y1 = Math.min(cellsY - 1, Math.round(sprite.getV1() * height) - 1 >> shift);
				cellRange[i * 4] = x0;
				cellRange[i * 4 + 1] = y0;
				cellRange[i * 4 + 2] = x1;
				cellRange[i * 4 + 3] = y1;
				for (int cy = y0; cy <= y1; cy++) {
					for (int cx = x0; cx <= x1; cx++) count[cy * cellsX + cx + 1]++;
				}
			}
			for (int c = 1; c < count.length; c++) count[c] += count[c - 1];
			start = count.clone();
			items = new int[count[count.length - 1]];
			int[] fill = Arrays.copyOf(count, count.length - 1);
			for (int i = 0; i < list.length; i++) {
				for (int cy = cellRange[i * 4 + 1]; cy <= cellRange[i * 4 + 3]; cy++) {
					for (int cx = cellRange[i * 4]; cx <= cellRange[i * 4 + 2]; cx++) items[fill[cy * cellsX + cx]++] = i;
				}
			}
		}

		/** The texture at u, v, or -1. */
		int find(float u, float v) {
			if (!(u >= 0 && u < 1 && v >= 0 && v < 1)) return -1;
			int cx = Math.min(cellsX - 1, (int) (u * width) >> shift), cy = Math.min(cellsY - 1, (int) (v * height) >> shift);
			int c = cy * cellsX + cx;
			for (int k = start[c]; k < start[c + 1]; k++) {
				int i = items[k];
				if (u >= rects[i * 4] && u < rects[i * 4 + 2] && v >= rects[i * 4 + 1] && v < rects[i * 4 + 3]) return i;
			}
			return -1;
		}
	}
}
