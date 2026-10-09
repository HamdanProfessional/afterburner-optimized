package com.afterburner.client.render;

import com.afterburner.Features;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;

/**
 * Chunk vertices in 16 bytes instead of vanilla's 28. Drawing chunks on integrated graphics is mostly reading and
 * transforming vertices, so less to read is faster. Water, glass and ice too, unless Improved Transparency is on (its
 * pipelines only take vanilla vertices).
 * <p>
 * Position: three 16-bit numbers in 1/2048 blocks from 8 blocks before the section's corner (so -8 to 24 blocks),
 * and in the fourth the section's place in its region (low byte) and the block light (high byte). Then the color, with
 * the sky light where its alpha was (chunk colors are opaque), and the texture position as two 16-bit fractions of the
 * atlas instead of two floats. Sections whose vertices don't fit (a see-through color, light past 255) stay as they are.
 * <p>
 * While a shader pack is on, sections are built in {@link #EXTENDED_FORMAT} instead: the same first 16 bytes, then what packs
 * read about terrain (see {@link TerrainExtras}), in the 28 bytes a vanilla vertex takes.
 */
public final class CompactVertices {
	public static final boolean ENABLED = Features.COMPACT_VERTICES.enabled() && Features.CHUNK_BATCHING.enabled();
	public static final VertexFormat FORMAT = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGBA16_UINT)
			.addAttribute("Color", GpuFormat.RGBA8_UNORM)
			.addAttribute("UV0", GpuFormat.RG16_UNORM)
			.build();
	public static final int SIZE = 16;
	/**
	 * For shader packs: {@link #FORMAT}, then the middle of the quad's texture (atlas fractions); the quad's normal and tangent
	 * (octahedral, two bytes each); and the pack's block ID + 1 (0 for none) with flags (1: a fluid, 2: the tangent's
	 * handedness is negative) and, above them, the quad's block in its section (4 bits each of x, y, z from bit 2) for
	 * at_midBlock.
	 */
	public static final VertexFormat EXTENDED_FORMAT = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGBA16_UINT)
			.addAttribute("Color", GpuFormat.RGBA8_UNORM)
			.addAttribute("UV0", GpuFormat.RG16_UNORM)
			.addAttribute("AbMidTex", GpuFormat.RG16_UNORM)
			.addAttribute("AbNormal", GpuFormat.RGBA8_SNORM)
			.addAttribute("AbBlock", GpuFormat.RG16_UINT)
			.build();
	/** How a mesh layer's vertices are stored. */
	public static final int VANILLA = 0, COMPACT = 1, EXTENDED = 2;
	private static final int BLOCK_SIZE = 28;
	private static final float STEPS = 2048.0F, BEFORE = 8.0F, LIMIT = 65535.0F / STEPS - BEFORE;
	private static final Identifier SHADER = Identifier.fromNamespaceAndPath("afterburner", "core/compact_terrain");
	private static @Nullable RenderPipeline solid, cutout, translucent, solidExtended, cutoutExtended, translucentExtended;

	private CompactVertices() {
	}

	/** The pipeline for vertices of this layer stored as {@code format} ({@link #COMPACT}, {@link #EXTENDED}), or null for none of ours. */
	public static @Nullable RenderPipeline pipeline(ChunkSectionLayer layer, int format) {
		if (format == EXTENDED) {
			return switch (layer) {
				case SOLID -> solidExtended != null ? solidExtended : (solidExtended = build("solid", null, ColorTargetState.DEFAULT, true));
				case CUTOUT -> cutoutExtended != null ? cutoutExtended : (cutoutExtended = build("cutout", 0.5F, ColorTargetState.DEFAULT, true));
				case TRANSLUCENT -> translucentExtended != null ? translucentExtended
						: (translucentExtended = build("translucent", 0.1F, new ColorTargetState(BlendFunction.TRANSLUCENT), true));
				default -> null;
			};
		}
		if (format != COMPACT) return null;
		return switch (layer) {
			case SOLID -> solid != null ? solid : (solid = build("solid", null, ColorTargetState.DEFAULT, false));
			case CUTOUT -> cutout != null ? cutout : (cutout = build("cutout", 0.5F, ColorTargetState.DEFAULT, false));
			case TRANSLUCENT -> translucent != null ? translucent : (translucent = build("translucent", 0.1F, new ColorTargetState(BlendFunction.TRANSLUCENT), false));
			default -> null;
		};
	}

	/** Bytes per vertex of a layer stored as {@code format}; {@code vanilla} is the layer's own. */
	public static int vertexSize(int format, int vanilla) {
		return format == COMPACT ? SIZE : vanilla;
	}

	/**
	 * Like vanilla's {@code SOLID_TERRAIN}, {@code CUTOUT_TERRAIN} and {@code TRANSLUCENT_TERRAIN}, with the compact format (or
	 * the extended one, whose first part the same vertex shader reads) and its vertex shader.
	 */
	private static RenderPipeline build(String name, @Nullable Float alphaCutout, ColorTargetState target, boolean extended) {
		RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
				.withLocation(Identifier.fromNamespaceAndPath("afterburner", "pipeline/compact_" + name + "_terrain" + (extended ? "_extended" : "")))
				.withVertexShader(SHADER)
				.withVertexBinding(0, extended ? EXTENDED_FORMAT : FORMAT)
				.withColorTargetState(target);
		if (alphaCutout != null) builder.withShaderDefine("ALPHA_CUTOUT", alphaCutout);
		return builder.build();
	}

	public static boolean appliesTo(ChunkSectionLayer layer) {
		return ENABLED && stored(layer);
	}

	/** Whether the layer can be stored in {@link #EXTENDED_FORMAT} (when its section was built for a pack). */
	public static boolean extendedAppliesTo(ChunkSectionLayer layer) {
		return TerrainExtras.ENABLED && stored(layer);
	}

	private static boolean stored(ChunkSectionLayer layer) {
		boolean stored = layer == ChunkSectionLayer.SOLID || layer == ChunkSectionLayer.CUTOUT
				|| layer == ChunkSectionLayer.TRANSLUCENT && !Minecraft.getInstance().options.improvedTransparency().get();
		return stored && layer.pipeline(false).getVertexFormatBinding(0) == DefaultVertexFormat.BLOCK;
	}

	/**
	 * Rewrites {@code vertexCount} vanilla block vertices, relative to the region's corner, into compact ones at the start
	 * of the same buffer. Returns false, changing nothing, if one of them doesn't fit.
	 *
	 * @param slot the section's place in its region ({@link ChunkRegions#slot})
	 */
	public static boolean convert(ByteBuffer v, int vertexCount, int slot) {
		float dx = (slot & 7) * 16, dy = (slot >> 3 & 3) * 16, dz = (slot >> 5 & 7) * 16;
		int end = vertexCount * BLOCK_SIZE;
		if (!fits(v, vertexCount, slot)) return false;
		// Each compact vertex is written no further than where its own (already read) vertex started.
		for (int from = 0, to = 0; from < end; from += BLOCK_SIZE, to += SIZE) {
			float x = v.getFloat(from) - dx, y = v.getFloat(from + 4) - dy, z = v.getFloat(from + 8) - dz;
			int color = v.getInt(from + 12);
			float u = v.getFloat(from + 16), w = v.getFloat(from + 20);
			int light = v.getInt(from + 24);
			v.putShort(to, (short) Math.round((x + BEFORE) * STEPS));
			v.putShort(to + 2, (short) Math.round((y + BEFORE) * STEPS));
			v.putShort(to + 4, (short) Math.round((z + BEFORE) * STEPS));
			v.putShort(to + 12, (short) Math.round(u * 65535.0F));
			v.putShort(to + 14, (short) Math.round(w * 65535.0F));
			// Block light (low byte of the first short) above the slot, sky light (of the second) in place of alpha.
			v.putShort(to + 6, (short) (slot | (light & 0xFF) << 8));
			v.putInt(to + 8, color & 0x00FFFFFF | (light >>> 16 & 0xFF) << 24);
		}
		return true;
	}

	/** Whether all {@code vertexCount} vanilla vertices fit the compact position, color and light. */
	private static boolean fits(ByteBuffer v, int vertexCount, int slot) {
		float dx = (slot & 7) * 16, dy = (slot >> 3 & 3) * 16, dz = (slot >> 5 & 7) * 16;
		int end = vertexCount * BLOCK_SIZE;
		if (v.limit() < end) return false;
		for (int at = 0; at < end; at += BLOCK_SIZE) {
			float x = v.getFloat(at) - dx, y = v.getFloat(at + 4) - dy, z = v.getFloat(at + 8) - dz;
			float u = v.getFloat(at + 16), w = v.getFloat(at + 20);
			if (!(x >= -BEFORE && x <= LIMIT && y >= -BEFORE && y <= LIMIT && z >= -BEFORE && z <= LIMIT && u >= 0 && u <= 1 && w >= 0 && w <= 1)) {
				return false;
			}
			// The color's alpha (its last byte) must be opaque, and block and sky light (two shorts) fit a byte each.
			if (v.get(at + 15) != (byte) 0xFF || (v.getInt(at + 24) & 0xFF00FF00) != 0) return false;
		}
		return true;
	}

	/**
	 * Rewrites {@code vertexCount} vanilla block vertices (quads), relative to the region's corner, into {@link #EXTENDED_FORMAT}
	 * in place. {@code runs} are the section build's block runs of the layer ({@link TerrainExtras.Recorder}). Returns false,
	 * changing nothing, if one of them doesn't fit.
	 */
	public static boolean convertExtended(ByteBuffer v, int vertexCount, int slot, int @Nullable [] runs) {
		if ((vertexCount & 3) != 0 || !fits(v, vertexCount, slot)) return false;
		float dx = (slot & 7) * 16, dy = (slot >> 3 & 3) * 16, dz = (slot >> 5 & 7) * 16;
		float[] p = new float[12], uv = new float[8], mid = new float[2];
		int[] color = new int[4], light = new int[4];
		int run = 0, value = 0;
		for (int q = 0; q < vertexCount; q += 4) {
			// A quad's four vertices come from one block.
			while (runs != null && run < runs.length && runs[run] <= q) {
				value = runs[run + 1];
				run += 2;
			}
			for (int j = 0; j < 4; j++) {
				int at = (q + j) * BLOCK_SIZE;
				p[j * 3] = v.getFloat(at) - dx;
				p[j * 3 + 1] = v.getFloat(at + 4) - dy;
				p[j * 3 + 2] = v.getFloat(at + 8) - dz;
				color[j] = v.getInt(at + 12);
				uv[j * 2] = v.getFloat(at + 16);
				uv[j * 2 + 1] = v.getFloat(at + 20);
				light[j] = v.getInt(at + 24);
			}
			// The normal, across the diagonals (the corners go round counterclockwise seen from the front).
			float ax = p[6] - p[0], ay = p[7] - p[1], az = p[8] - p[2];
			float bx = p[9] - p[3], by = p[10] - p[4], bz = p[11] - p[5];
			float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
			float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (length < 1.0E-8F) {
				nx = 0;
				ny = 1;
				nz = 0;
			} else {
				nx /= length;
				ny /= length;
				nz /= length;
			}
			// The tangent points the way u grows; the handedness says which way v does, as packs (and Iris) take it.
			float e1x = p[3] - p[0], e1y = p[4] - p[1], e1z = p[5] - p[2];
			float e2x = p[6] - p[0], e2y = p[7] - p[1], e2z = p[8] - p[2];
			float du1 = uv[2] - uv[0], dv1 = uv[3] - uv[1], du2 = uv[4] - uv[0], dv2 = uv[5] - uv[1];
			float det = du1 * dv2 - du2 * dv1;
			float tx = 1, ty = 0, tz = 0, sx = 0, sy = 0, sz = 0;
			if (Math.abs(det) > 1.0E-14F) {
				float f = 1 / det;
				tx = f * (dv2 * e1x - dv1 * e2x);
				ty = f * (dv2 * e1y - dv1 * e2y);
				tz = f * (dv2 * e1z - dv1 * e2z);
				sx = f * (du1 * e2x - du2 * e1x);
				sy = f * (du1 * e2y - du2 * e1y);
				sz = f * (du1 * e2z - du2 * e1z);
			}
			float d = tx * nx + ty * ny + tz * nz;
			tx -= nx * d;
			ty -= ny * d;
			tz -= nz * d;
			length = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
			if (length < 1.0E-6F) {
				// Any direction along the face.
				if (Math.abs(nx) < 0.9F) {
					tx = 0;
					ty = nz;
					tz = -ny;
				} else {
					tx = -nz;
					ty = 0;
					tz = nx;
				}
				length = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
			}
			tx /= length;
			ty /= length;
			tz /= length;
			boolean flipped = (ny * tz - nz * ty) * sx + (nz * tx - nx * tz) * sy + (nx * ty - ny * tx) * sz < 0;
			if (!TerrainExtras.spriteCenter((uv[0] + uv[4]) * 0.5F, (uv[1] + uv[5]) * 0.5F, mid)) {
				mid[0] = (Math.min(Math.min(uv[0], uv[2]), Math.min(uv[4], uv[6])) + Math.max(Math.max(uv[0], uv[2]), Math.max(uv[4], uv[6]))) * 0.5F;
				mid[1] = (Math.min(Math.min(uv[1], uv[3]), Math.min(uv[5], uv[7])) + Math.max(Math.max(uv[1], uv[3]), Math.max(uv[5], uv[7]))) * 0.5F;
			}
			int normal = octahedral(nx, ny, nz), tangent = octahedral(tx, ty, tz);
			int flags = (value & TerrainExtras.FLUID) != 0 ? 1 : 0;
			if (flipped) flags |= 2;
			// The block the quad belongs to: just behind its middle (a face is on its block's side; a cross is inside it).
			int blockX = Math.clamp((int) Math.floor((p[0] + p[3] + p[6] + p[9]) * 0.25F - nx * 0.01F), 0, 15);
			int blockY = Math.clamp((int) Math.floor((p[1] + p[4] + p[7] + p[10]) * 0.25F - ny * 0.01F), 0, 15);
			int blockZ = Math.clamp((int) Math.floor((p[2] + p[5] + p[8] + p[11]) * 0.25F - nz * 0.01F), 0, 15);
			flags |= blockX << 2 | blockY << 6 | blockZ << 10;
			for (int j = 0; j < 4; j++) {
				int at = (q + j) * BLOCK_SIZE;
				v.putShort(at, (short) Math.round((p[j * 3] + BEFORE) * STEPS));
				v.putShort(at + 2, (short) Math.round((p[j * 3 + 1] + BEFORE) * STEPS));
				v.putShort(at + 4, (short) Math.round((p[j * 3 + 2] + BEFORE) * STEPS));
				v.putShort(at + 6, (short) (slot | (light[j] & 0xFF) << 8));
				v.putInt(at + 8, color[j] & 0x00FFFFFF | (light[j] >>> 16 & 0xFF) << 24);
				v.putShort(at + 12, (short) Math.round(uv[j * 2] * 65535.0F));
				v.putShort(at + 14, (short) Math.round(uv[j * 2 + 1] * 65535.0F));
				v.putShort(at + 16, (short) Math.round(Math.clamp(mid[0], 0.0F, 1.0F) * 65535.0F));
				v.putShort(at + 18, (short) Math.round(Math.clamp(mid[1], 0.0F, 1.0F) * 65535.0F));
				v.put(at + 20, (byte) normal);
				v.put(at + 21, (byte) (normal >> 8));
				v.put(at + 22, (byte) tangent);
				v.put(at + 23, (byte) (tangent >> 8));
				v.putShort(at + 24, (short) (value & 0xFFFF));
				v.putShort(at + 26, (short) flags);
			}
		}
		return true;
	}

	/** A unit vector folded onto an octahedron: two signed bytes (x in the low one), as the shader's ab_unoct reads them. */
	private static int octahedral(float x, float y, float z) {
		float s = Math.abs(x) + Math.abs(y) + Math.abs(z);
		float ox = x / s, oy = y / s;
		if (z < 0) {
			float fx = (1 - Math.abs(oy)) * (ox >= 0 ? 1 : -1), fy = (1 - Math.abs(ox)) * (oy >= 0 ? 1 : -1);
			ox = fx;
			oy = fy;
		}
		return (Math.round(Math.clamp(ox, -1.0F, 1.0F) * 127.0F) & 0xFF) | (Math.round(Math.clamp(oy, -1.0F, 1.0F) * 127.0F) & 0xFF) << 8;
	}
}

