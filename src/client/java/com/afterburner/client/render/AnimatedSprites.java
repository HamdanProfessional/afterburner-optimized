package com.afterburner.client.render;

import com.afterburner.Features;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Animated block textures (water, lava, fire, sea lanterns...) only move on while something on screen uses them. Vanilla
 * draws each one into the block atlas again every tick, wherever it is: one draw per texture and mip level, a millisecond
 * or two on the frames that tick, more with a big resource pack.
 *
 * <p>What chunks use is read from their finished meshes, whatever built them: the middle of each quad's texture
 * coordinates is inside the one texture it shows. The chunk batcher puts together the ones of the sections on screen
 * ({@link #setVisible}). Everything else drawn with block textures (items, falling and moving blocks, fire on mobs, the
 * portal and in-wall overlays, sprites in screens) marks its texture as it's drawn ({@link #seen}).
 *
 * <p>A texture that was skipped draws the frame it's at as soon as it's seen again.
 */
public final class AnimatedSprites {
	public static final boolean ENABLED = Features.VISIBLE_ANIMATIONS.enabled();
	/** Ticks a texture keeps moving after it was last seen: the sections on screen change with every turn of the camera. */
	private static final int GRACE = 10;
	/** Stands for a section whose mesh couldn't be read: while it's on screen, everything moves. */
	public static final SpriteContents[] UNKNOWN = new SpriteContents[0];

	/** Texture animation updates, and the texture redraws in them done and left out, for the benchmark report. Render thread only. */
	public static long updates, redrawn, skipped;

	/** Counts the texture animation updates (one per frame that ticks). Render thread only, as are the fields below. */
	private static int tick;
	/** The update everything was last seen in (a section with an unreadable mesh on screen). */
	private static int allSeenAt = Integer.MIN_VALUE / 2;
	private static SpriteContents[] visible = new SpriteContents[0];
	private static boolean visibleUnknown;
	/** The sections' textures last handed over, put together at the next update (frames come more often than updates). */
	private static SpriteContents @Nullable [] @Nullable [] shown;
	private static int shownCount;
	private static boolean shownChanged;
	private static int listStamp;
	private static volatile @Nullable Lookup lookup;

	private AnimatedSprites() {
	}

	/** Before the textures update: the ones in the sections on screen were seen. */
	public static void beginTick() {
		tick++;
		updates++;
		if (shownChanged) {
			shownChanged = false;
			if (shown != null) collect(shown, shownCount);
		}
		if (visibleUnknown) allSeenAt = tick;
		for (SpriteContents contents : visible) seen(contents);
	}

	/** Whether this texture hasn't been on screen for a while, so its update can wait (render thread). */
	public static boolean skips(SpriteContents contents) {
		return tick - ((SeenSprite) contents).afterburner$seenAt() > GRACE && tick - allSeenAt > GRACE;
	}

	/** Something is drawing this texture now (render thread). */
	public static void seen(TextureAtlasSprite sprite) {
		seen(sprite.contents());
	}

	public static void seen(SpriteContents contents) {
		SeenSprite seen = (SeenSprite) contents;
		// Only written when it changes: the same few textures are marked over and over.
		if (seen.afterburner$seenAt() != tick) seen.afterburner$see(tick);
	}

	/**
	 * The animated textures of the sections on screen now: {@code sections[0 .. count)}, from the chunk batcher's build. The
	 * array is read at the next update, so it must stay as it is until then or until this is called again.
	 */
	public static void setVisible(SpriteContents @Nullable [] @Nullable [] sections, int count) {
		shown = sections;
		shownCount = count;
		shownChanged = true;
	}

	private static void collect(SpriteContents @Nullable [] @Nullable [] sections, int count) {
		int stamp = ++listStamp;
		boolean unknown = false;
		List<SpriteContents> found = new ArrayList<>();
		SpriteContents[] last = null;
		for (int i = 0; i < count; i++) {
			SpriteContents[] used = sections[i];
			// Sections showing the same textures share one array (see scan), and neighbors mostly show the same.
			if (used == null || used == last) continue;
			last = used;
			if (used == UNKNOWN) {
				unknown = true;
				continue;
			}
			for (SpriteContents contents : used) {
				SeenSprite seen = (SeenSprite) contents;
				if (seen.afterburner$listed() != stamp) {
					seen.afterburner$list(stamp);
					found.add(contents);
				}
			}
		}
		visible = found.toArray(new SpriteContents[0]);
		visibleUnknown = unknown;
	}

	/** The block atlas was (re)made: where its animated textures are now. */
	public static void atlasUploaded(Collection<TextureAtlasSprite> sprites, int width, int height) {
		List<TextureAtlasSprite> animated = new ArrayList<>();
		for (TextureAtlasSprite sprite : sprites) {
			if (sprite.isAnimated()) animated.add(sprite);
		}
		lookup = animated.isEmpty() || width <= 0 || height <= 0 || animated.size() > Short.MAX_VALUE ? null : new Lookup(animated, width, height);
	}

	/**
	 * The animated textures a freshly built section's meshes show, null for none, or {@link #UNKNOWN}. On the chunk
	 * builder threads.
	 */
	public static SpriteContents @Nullable [] scan(Collection<MeshData> meshes) {
		Lookup lookup = AnimatedSprites.lookup;
		if (lookup == null) return null;
		long[] found = null;
		for (MeshData mesh : meshes) {
			MeshData.DrawState state = mesh.drawState();
			VertexFormatElement uv = state.format().getElement("UV0");
			if (uv == null || uv.format() != GpuFormat.RG32_FLOAT || state.primitiveTopology() != PrimitiveTopology.QUADS) return UNKNOWN;
			ByteBuffer vertices = mesh.vertexBuffer();
			int stride = state.format().getVertexSize(), quads = state.vertexCount() / 4;
			if (vertices.limit() < quads * 4 * stride) return UNKNOWN;
			for (int q = 0, a = uv.offset(); q < quads; q++, a += 4 * stride) {
				// Corners 0 and 2 are across from each other.
				int c = a + 2 * stride;
				int i = lookup.find((vertices.getFloat(a) + vertices.getFloat(c)) * 0.5F, (vertices.getFloat(a + 4) + vertices.getFloat(c + 4)) * 0.5F);
				if (i < 0) continue;
				if (found == null) found = new long[(lookup.sprites.length + 63) >> 6];
				found[i >> 6] |= 1L << i;
			}
		}
		if (found == null) return null;
		return lookup.shared.computeIfAbsent(new Bits(found), bits -> {
			List<SpriteContents> used = new ArrayList<>();
			for (int i = 0; i < lookup.sprites.length; i++) {
				if ((bits.bits[i >> 6] & 1L << i) != 0) used.add(lookup.sprites[i]);
			}
			return used.toArray(new SpriteContents[0]);
		});
	}

	/** A set of animated textures, by their index in the lookup. */
	private record Bits(long[] bits) {
		@Override
		public boolean equals(Object o) {
			return o instanceof Bits other && Arrays.equals(bits, other.bits);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(bits);
		}
	}

	/** Where the animated textures are in the block atlas: a grid of cells, each naming the one animated texture in it, if one. */
	private static final class Lookup {
		final SpriteContents[] sprites;
		/** u0, v0, u1, v1 of each. */
		final float[] rects;
		final int width, height, shift, cellsX, cellsY;
		/** 0 for no animated texture, i + 1 for only texture i, -1 for more than one. */
		final short[] cells;
		/** One array for each set of textures sections show, so the sections showing the same share it. */
		final ConcurrentHashMap<Bits, SpriteContents[]> shared = new ConcurrentHashMap<>();

		Lookup(List<TextureAtlasSprite> animated, int width, int height) {
			this.width = width;
			this.height = height;
			// Cells of a power of two pixels across, about a million of them at most.
			int shift = 0;
			while ((long) ((width - 1 >> shift) + 1) * ((height - 1 >> shift) + 1) > 1 << 20) shift++;
			this.shift = shift;
			cellsX = (width - 1 >> shift) + 1;
			cellsY = (height - 1 >> shift) + 1;
			cells = new short[cellsX * cellsY];
			sprites = new SpriteContents[animated.size()];
			rects = new float[animated.size() * 4];
			for (int i = 0; i < sprites.length; i++) {
				TextureAtlasSprite sprite = animated.get(i);
				sprites[i] = sprite.contents();
				rects[i * 4] = sprite.getU0();
				rects[i * 4 + 1] = sprite.getV0();
				rects[i * 4 + 2] = sprite.getU1();
				rects[i * 4 + 3] = sprite.getV1();
				int x0 = Math.round(sprite.getU0() * width), x1 = Math.round(sprite.getU1() * width);
				int y0 = Math.round(sprite.getV0() * height), y1 = Math.round(sprite.getV1() * height);
				for (int cy = Math.max(0, y0 >> shift); cy <= Math.min(cellsY - 1, y1 - 1 >> shift); cy++) {
					for (int cx = Math.max(0, x0 >> shift); cx <= Math.min(cellsX - 1, x1 - 1 >> shift); cx++) {
						int cell = cy * cellsX + cx;
						cells[cell] = cells[cell] == 0 ? (short) (i + 1) : -1;
					}
				}
			}
		}

		/** The animated texture at u, v, or -1. */
		int find(float u, float v) {
			if (!(u >= 0 && u < 1 && v >= 0 && v < 1)) return -1;
			int cx = Math.min(cellsX - 1, (int) (u * width) >> shift), cy = Math.min(cellsY - 1, (int) (v * height) >> shift);
			int c = cells[cy * cellsX + cx];
			if (c == 0) return -1;
			if (c > 0) return inside(c - 1, u, v) ? c - 1 : -1;
			for (int i = 0; i < sprites.length; i++) {
				if (inside(i, u, v)) return i;
			}
			return -1;
		}

		private boolean inside(int i, float u, float v) {
			return u >= rects[i * 4] && u < rects[i * 4 + 2] && v >= rects[i * 4 + 1] && v < rects[i * 4 + 3];
		}
	}
}
