package com.afterburner.client.render;

import com.afterburner.Features;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Chunk vertices in 16 bytes instead of vanilla's 32. Drawing chunks on integrated graphics is mostly reading and
 * transforming vertices, so less to read is faster.
 * <p>
 * Position: three 16-bit numbers in 1/2048 blocks from 8 blocks before the section's corner (so -8 to 24 blocks),
 * and in the fourth the section's place in its region (low byte) and the block light (high byte). Then the color, with
 * the sky light where its alpha was (chunk colors are opaque), and the texture position as two 16-bit fractions of the
 * atlas instead of two floats. Sections whose vertices don't fit (a see-through color, light past 255) stay as they are.
 * <p>
 * Each layer gets a shader of its own: vanilla's, with the vertex shader swapped for one that reads this format. A layer
 * whose vertex shader a resource pack changed stays in vanilla's format, so the pack's shader still draws it.
 * <p>
 * While a shader pack is on, sections are stored in the extended format instead: the same first 16 bytes, then what packs
 * read about terrain (see {@link TerrainExtras}), in the 32 bytes a vanilla vertex takes. The same shaders draw it (they
 * read the first 16 bytes) until the sections are built again without a pack.
 * <p>
 * The position encoding and the section slot follow Sodium's compact chunk vertex format (Sodium 0.5, CaffeineMC).
 */
public final class CompactVertices {
	private static final Logger LOGGER = LoggerFactory.getLogger("afterburner");
	public static final boolean ENABLED = Features.COMPACT_VERTICES.enabled() && Features.CHUNK_BATCHING.enabled();
	public static final int SIZE = 16;
	/**
	 * Bytes of an extended vertex: the compact one, then the middle of the quad's texture (atlas fractions); the quad's normal
	 * and tangent (octahedral, two bytes each); and the pack's block ID + 1 (0 for none) with flags (1: a fluid, 2: the
	 * tangent's handedness is negative) and, above them, the quad's block in its section (4 bits each of x, y, z from bit 2)
	 * for at_midBlock. 4 bytes left over, so it keeps the vanilla vertex's size.
	 */
	public static final int EXTENDED_SIZE = 32;
	/** Bytes of a vanilla block vertex: position, color, texture, light, normal. */
	public static final int BLOCK_SIZE = 32;
	private static final float STEPS = 2048.0F, BEFORE = 8.0F, LIMIT = 65535.0F / STEPS - BEFORE;
	/** Vanilla's shader of each chunk layer ({@link ChunkBatcher#LAYERS} order). */
	private static final String[] VANILLA = {"rendertype_solid", "rendertype_cutout_mipped", "rendertype_cutout", "rendertype_translucent", "rendertype_tripwire"};
	private static final String VERTEX = "afterburner_compact_terrain";
	private static final ShaderInstance[] shaders = new ShaderInstance[VANILLA.length];
	/** Whether new meshes of a layer are stored compact: its shader loaded and no resource pack changed vanilla's. */
	private static final boolean[] usable = new boolean[VANILLA.length];

	private CompactVertices() {
	}

	/** Whether new meshes of this layer are stored compact. */
	public static boolean appliesTo(int layer) {
		return ENABLED && usable[layer];
	}

	/** Whether the layer can be stored extended (when its section was built for a pack): it has a shader to draw it. */
	public static boolean extendedAppliesTo(int layer) {
		return TerrainExtras.ENABLED && shaders[layer] != null;
	}

	/** The shader for this layer's compact vertices, or null if it couldn't be made. Render thread only. */
	public static @Nullable ShaderInstance shader(int layer) {
		return shaders[layer];
	}

	/**
	 * Makes the shaders again after the game's own were (re)loaded from {@code provider}. Render thread only. Meshes
	 * already stored keep their format; the chunks are built again after a resource reload anyway.
	 */
	public static void reload(ResourceProvider provider) {
		if (!ENABLED && !TerrainExtras.ENABLED) return;
		for (int i = 0; i < shaders.length; i++) {
			if (shaders[i] != null) shaders[i].close();
			shaders[i] = null;
			usable[i] = false;
		}
		Resource vertexShader = classpath(provider);
		if (vertexShader == null) return;
		for (int i = 0; i < VANILLA.length; i++) {
			String name = VANILLA[i];
			try {
				Resource json = provider.getResource(location(name + ".json")).orElse(null);
				if (json == null) continue;
				JsonObject definition;
				try (Reader in = json.openAsReader()) {
					definition = JsonParser.parseReader(in).getAsJsonObject();
				}
				boolean vanillaVertex = name.equals(definition.get("vertex").getAsString())
						&& provider.getResource(location(name + ".vsh")).map(r -> "vanilla".equals(r.sourcePackId())).orElse(false);
				definition.addProperty("vertex", VERTEX);
				byte[] bytes = definition.toString().getBytes(StandardCharsets.UTF_8);
				Resource ours = new Resource(json.source(), () -> new ByteArrayInputStream(bytes));
				String shaderName = "afterburner_compact_" + name.substring("rendertype_".length());
				ResourceLocation ourJson = location(shaderName + ".json"), ourVertex = location(VERTEX + ".vsh");
				ResourceProvider withOurs = location -> location.equals(ourJson) ? Optional.of(ours)
						: location.equals(ourVertex) ? Optional.of(vertexShader) : provider.getResource(location);
				// Attributes are bound in the block format's order: Position, Color and UV0 come first, as the shader reads them.
				shaders[i] = new ShaderInstance(withOurs, shaderName, DefaultVertexFormat.BLOCK);
				usable[i] = vanillaVertex;
			} catch (Exception e) {
				LOGGER.warn("[Afterburner] Couldn't make the compact chunk shader for {}, its chunks keep vanilla's vertices: {}", name, e.toString());
			}
		}
	}

	/** The vertex shader, from Afterburner's jar (it isn't for resource packs to change). */
	private static @Nullable Resource classpath(ResourceProvider provider) {
		byte[] source;
		try (InputStream in = CompactVertices.class.getResourceAsStream("/assets/afterburner/shaders/core/compact_terrain.vsh")) {
			if (in == null) throw new java.io.FileNotFoundException("compact_terrain.vsh");
			source = in.readAllBytes();
		} catch (Exception e) {
			LOGGER.warn("[Afterburner] Couldn't read the compact chunk shader, chunks keep vanilla's vertices: {}", e.toString());
			return null;
		}
		// Shaders take the pack they come from for their error messages; vanilla's is as good as any.
		Resource vanilla = provider.getResource(location(VANILLA[0] + ".vsh")).orElse(null);
		return vanilla == null ? null : new Resource(vanilla.source(), () -> new ByteArrayInputStream(source));
	}

	private static ResourceLocation location(String file) {
		return ResourceLocation.withDefaultNamespace("shaders/core/" + file);
	}

	/**
	 * Rewrites {@code vertexCount} vanilla block vertices, relative to the section's corner, into compact ones at the start
	 * of the same buffer. Returns false, changing nothing, if one of them doesn't fit.
	 *
	 * @param slot the section's place in its region ({@link ChunkRegions#slot})
	 */
	public static boolean convert(ByteBuffer v, int vertexCount, int slot) {
		int end = vertexCount * BLOCK_SIZE;
		if (!fits(v, vertexCount)) return false;
		// Each compact vertex is written no further than where its own (already read) vertex started.
		for (int from = 0, to = 0; from < end; from += BLOCK_SIZE, to += SIZE) {
			float x = v.getFloat(from), y = v.getFloat(from + 4), z = v.getFloat(from + 8);
			int color = v.getInt(from + 12);
			float u = v.getFloat(from + 16), w = v.getFloat(from + 20);
			int light = v.getInt(from + 24);
			v.putShort(to, (short) Math.round((x + BEFORE) * STEPS));
			v.putShort(to + 2, (short) Math.round((y + BEFORE) * STEPS));
			v.putShort(to + 4, (short) Math.round((z + BEFORE) * STEPS));
			// Block light (low byte of the first short) above the slot, sky light (of the second) in place of alpha.
			v.putShort(to + 6, (short) (slot | (light & 0xFF) << 8));
			v.putInt(to + 8, color & 0x00FFFFFF | (light >>> 16 & 0xFF) << 24);
			v.putShort(to + 12, (short) Math.round(u * 65535.0F));
			v.putShort(to + 14, (short) Math.round(w * 65535.0F));
		}
		return true;
	}

	/**
	 * Rewrites {@code vertexCount} vanilla block vertices (quads), relative to the section's corner, into the extended format
	 * in place. {@code runs} are the section build's block runs of the layer ({@link TerrainExtras.Recorder}). Returns false,
	 * changing nothing, if one of them doesn't fit.
	 */
	public static boolean convertExtended(ByteBuffer v, int vertexCount, int slot, int @Nullable [] runs) {
		if ((vertexCount & 3) != 0 || !fits(v, vertexCount)) return false;
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
				p[j * 3] = v.getFloat(at);
				p[j * 3 + 1] = v.getFloat(at + 4);
				p[j * 3 + 2] = v.getFloat(at + 8);
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
			// The tangent points the way u grows; the handedness says which way v does, as shader packs expect it.
			// Tangent from the texture coordinates: E. Lengyel, "Computing Tangent Space Basis Vectors for an Arbitrary Mesh" (2001).
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
				int at = (q + j) * EXTENDED_SIZE;
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
				v.putInt(at + 28, 0);
			}
		}
		return true;
	}

	/**
	 * Octahedral encoding: Cigolle et al., "A Survey of Efficient Representations for Independent Unit Vectors" (JCGT 2014),
	 * as in K. Narkowicz, "Octahedron normal vector encoding" (2014).
	 * <p>
	 * A unit vector folded onto an octahedron: two signed bytes (x in the low one), as the shader's ab_unoct reads them.
	 */
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

	/** Whether all {@code vertexCount} vanilla vertices fit the compact position, color and light. */
	private static boolean fits(ByteBuffer v, int vertexCount) {
		int end = vertexCount * BLOCK_SIZE;
		if (v.limit() < end) return false;
		for (int at = 0; at < end; at += BLOCK_SIZE) {
			float x = v.getFloat(at), y = v.getFloat(at + 4), z = v.getFloat(at + 8);
			float u = v.getFloat(at + 16), w = v.getFloat(at + 20);
			if (!(x >= -BEFORE && x <= LIMIT && y >= -BEFORE && y <= LIMIT && z >= -BEFORE && z <= LIMIT && u >= 0 && u <= 1 && w >= 0 && w <= 1)) {
				return false;
			}
			// The color's alpha (its last byte) must be opaque, and block and sky light (two shorts) fit a byte each.
			if (v.get(at + 15) != (byte) 0xFF || (v.getInt(at + 24) & 0xFF00FF00) != 0) return false;
		}
		return true;
	}
}
