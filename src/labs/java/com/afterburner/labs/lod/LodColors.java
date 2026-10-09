package com.afterburner.labs.lod;

import com.afterburner.labs.mixin.lod.SpriteContentsAccessor;
import com.afterburner.labs.mixin.lod.TextureAtlasAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The color of each face of a far terrain voxel: the average of the block model's textures on that side (weighted by how
 * much of each texture is opaque), with the part that's tinted (grass, leaves) tinted for the column's biome. The texture
 * averages are taken on the render thread whenever the models are reloaded ({@link #refresh}); the rest is the worker's.
 */
final class LodColors {
	/** What the render thread hands the worker: the models, and per block atlas sprite its average color (alpha: coverage). */
	record Textures(BlockStateModelSet models, IdentityHashMap<TextureAtlasSprite, Integer> sprites, int water, int lava, BlockColors blockColors) {
	}

	private static volatile @Nullable Textures textures;
	private static @Nullable BlockStateModelSet seen;

	/** Render thread, once a frame: takes the texture averages again after a resource reload. */
	static void refresh(Minecraft mc) {
		BlockStateModelSet models = mc.getModelManager().getBlockStateModelSet();
		if (models == seen) return;
		seen = models;
		try {
			TextureAtlas atlas = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
			Map<Identifier, TextureAtlasSprite> byName = ((TextureAtlasAccessor) atlas).afterburner$texturesByName();
			IdentityHashMap<TextureAtlasSprite, Integer> sprites = new IdentityHashMap<>(byName.size() * 2);
			for (TextureAtlasSprite sprite : byName.values()) sprites.put(sprite, average(sprite.contents()));
			int water = average(atlas.getSprite(Identifier.withDefaultNamespace("block/water_still")).contents());
			int lava = average(atlas.getSprite(Identifier.withDefaultNamespace("block/lava_still")).contents());
			textures = new Textures(models, sprites, water, lava, mc.getBlockColors());
		} catch (RuntimeException e) {
			textures = null;
		}
	}

	static @Nullable Textures textures() {
		return textures;
	}

	/** The first frame's average color, alpha-weighted, and in the alpha byte how much of it is opaque. */
	private static int average(SpriteContents contents) {
		NativeImage image = ((SpriteContentsAccessor) contents).afterburner$originalImage();
		int w = contents.width(), h = contents.height();
		int step = Math.max(1, Math.max(w, h) / 16);
		long r = 0, g = 0, b = 0, a = 0, n = 0;
		try {
			for (int y = 0; y < h; y += step) {
				for (int x = 0; x < w; x += step) {
					int argb = image.getPixel(x, y);
					int alpha = argb >>> 24;
					r += (long) (argb >> 16 & 255) * alpha;
					g += (long) (argb >> 8 & 255) * alpha;
					b += (long) (argb & 255) * alpha;
					a += alpha;
					n++;
				}
			}
		} catch (RuntimeException e) {
			// A closed image (mid-reload): no color.
			return 0;
		}
		if (a == 0) return 0;
		int coverage = (int) Math.min(255, a / Math.max(1, n));
		return coverage << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
	}

	// ---- Worker ----

	private @Nullable Textures using;
	private @Nullable LodWorld forWorld;
	/** Per palette place: per face (in {@link Direction} order) the untinted color and the tinted part, then the tint layer. */
	private int[] @Nullable [] faces = new int[64][];
	/** Per palette place and biome: the tint, -1 (the default) when not worked out yet. */
	private final Long2IntOpenHashMap tints = new Long2IntOpenHashMap();
	{
		tints.defaultReturnValue(-1);
	}
	private final SingleThreadedRandomSource random = new SingleThreadedRandomSource(42L);
	private final List<BlockStateModelPart> parts = new ArrayList<>();
	private final TintGetter getter = new TintGetter();

	/** Starts a mesh: false if there are no textures to color it with yet. */
	boolean begin(LodWorld world) {
		Textures now = textures;
		if (now == null) return false;
		if (now != using || world != forWorld) {
			using = now;
			forWorld = world;
			faces = new int[64][];
			tints.clear();
		}
		return true;
	}

	/** The RGB color of a face of a voxel of that palette place in that biome. */
	int color(LodWorld world, int id, int biome, int face) {
		Textures t = using;
		byte kind = world.kind(id);
		if (kind == LodKinds.WATER) return tinted(0, t.water & 0xFFFFFF, tint(world, id, biome, -2));
		if (kind == LodKinds.LAVA) return t.lava & 0xFFFFFF;
		if (id >= faces.length) faces = java.util.Arrays.copyOf(faces, Math.max(id + 1, faces.length * 2));
		int[] f = faces[id];
		if (f == null) faces[id] = f = faces(world.state(id), t);
		int base = f[face], part = f[6 + face];
		if (part == 0) return base;
		return tinted(base, part, tint(world, id, biome, f[12]));
	}

	private static int tinted(int base, int part, int tint) {
		int r = (base >> 16 & 255) + (part >> 16 & 255) * (tint >> 16 & 255) / 255;
		int g = (base >> 8 & 255) + (part >> 8 & 255) * (tint >> 8 & 255) / 255;
		int b = (base & 255) + (part & 255) * (tint & 255) / 255;
		return Math.min(r, 255) << 16 | Math.min(g, 255) << 8 | Math.min(b, 255);
	}

	/** {@code layer} -2: the biome's water color. */
	private int tint(LodWorld world, int id, int biome, int layer) {
		long key = (long) id << 32 | biome & 0xFFFFFFFFL;
		int tint = tints.get(key);
		if (tint >= 0) return tint;
		tint = 0xFFFFFF;
		Biome b = world.biome(biome);
		if (b != null) {
			try {
				if (layer == -2) {
					tint = b.getWaterColor();
				} else if (layer >= 0) {
					BlockState state = world.state(id);
					BlockTintSource source = using.blockColors().getTintSource(state, layer);
					getter.biome = b;
					if (source != null) tint = source.colorInWorld(state, getter, BlockPos.ZERO);
				}
			} catch (RuntimeException e) {
				tint = 0xFFFFFF;
			}
		}
		tint &= 0xFFFFFF;
		tints.put(key, tint);
		return tint;
	}

	private int[] faces(BlockState state, Textures t) {
		int[] out = new int[13];
		out[12] = -1;
		parts.clear();
		try {
			random.setSeed(42L);
			t.models().get(state).collectParts(random, parts);
		} catch (RuntimeException e) {
			parts.clear();
		}
		float[] all = new float[7];
		float[][] sides = new float[6][7];
		for (BlockStateModelPart part : parts) {
			for (Direction d : Direction.values()) {
				for (BakedQuad q : part.getQuads(d)) add(sides[d.ordinal()], all, q, t, out);
			}
			for (BakedQuad q : part.getQuads(null)) add(sides[q.direction().ordinal()], all, q, t, out);
		}
		int fallback = 0;
		if (all[6] == 0) fallback = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
		for (int f = 0; f < 6; f++) {
			float[] s = sides[f][6] > 0 ? sides[f] : all;
			if (s[6] == 0) {
				out[f] = fallback;
				continue;
			}
			out[f] = rgb(s[0] / s[6], s[1] / s[6], s[2] / s[6]);
			out[6 + f] = rgb(s[3] / s[6], s[4] / s[6], s[5] / s[6]);
		}
		return out;
	}

	private static void add(float[] side, float[] all, BakedQuad q, Textures t, int[] out) {
		BakedQuad.MaterialInfo info = q.materialInfo();
		Integer c = t.sprites().get(info.sprite());
		if (c == null) return;
		float a = (c >>> 24) / 255.0F;
		if (a == 0) return;
		int at = info.tintIndex() >= 0 ? 3 : 0;
		if (info.tintIndex() >= 0 && out[12] < 0) out[12] = info.tintIndex();
		for (float[] s : new float[][]{side, all}) {
			s[at] += (c >> 16 & 255) * a;
			s[at + 1] += (c >> 8 & 255) * a;
			s[at + 2] += (c & 255) * a;
			s[6] += a;
		}
	}

	private static int rgb(float r, float g, float b) {
		return Math.min(255, Math.round(r)) << 16 | Math.min(255, Math.round(g)) << 8 | Math.min(255, Math.round(b));
	}

	/** A world of one biome, for the tint sources. */
	private static final class TintGetter implements BlockAndTintGetter {
		@Nullable Biome biome;

		@Override
		public CardinalLighting cardinalLighting() {
			return CardinalLighting.DEFAULT;
		}

		@Override
		public LevelLightEngine getLightEngine() {
			return LevelLightEngine.EMPTY;
		}

		@Override
		public int getBlockTint(BlockPos pos, ColorResolver color) {
			return biome == null ? -1 : color.getColor(biome, pos.getX(), pos.getZ());
		}

		@Override
		public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
			return null;
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			return Blocks.AIR.defaultBlockState();
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			return Fluids.EMPTY.defaultFluidState();
		}

		@Override
		public int getHeight() {
			return 0;
		}

		@Override
		public int getMinY() {
			return 0;
		}
	}
}
