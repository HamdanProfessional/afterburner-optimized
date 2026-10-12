package com.afterburner.bench.client;

import com.afterburner.bench.Reports;
import com.afterburner.bench.mixin.client.VertexBufferAccessor;
import com.afterburner.client.render.ChunkBatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.List;

/**
 * Measures how much of the terrain drawn is completely hidden behind nearer terrain, to see whether occlusion culling
 * would be worth it. Only with {@code -Dafterburner.occlusionProbe=true} (benchmarks), and with the chunk batcher on.
 * <p>
 * Every {@link #EVERY} frames, after the solid and cutout layers are drawn, the depth buffer is read back and every
 * section drawn is tested against it: a section is hidden if every pixel its box covers already has something nearer
 * than the nearest corner of the box. Reading the depth back stalls the graphics card, so the FPS of such a run
 * doesn't count.
 */
public final class OcclusionProbe {
	public static final boolean ENABLED = Boolean.getBoolean("afterburner.occlusionProbe");
	private static final int EVERY = 30;
	/** Boxes are grown by this much (blocks), so depth rounding can't hide a section that touches its own box. */
	private static final float GROW = 0.5F;
	private static final List<RenderType> LAYERS = ChunkBatcher.LAYERS;
	private static final String[] NAMES = new String[LAYERS.size()];
	/** With {@code -Dafterburner.occlusionProbeImage=true}, one look is saved as a picture with the hidden sections outlined (checks the math). */
	private static final boolean IMAGE = Boolean.getBoolean("afterburner.occlusionProbeImage");
	private static final int IMAGE_AT = 40;

	/** Whether the frames are counted (during the benchmark's measuring). */
	public static boolean measuring;
	private static long frames, probes, sections, hiddenSections, probeNanos;
	private static final long[] quads = new long[LAYERS.size()], hiddenQuads = new long[LAYERS.size()];
	/** Quads in sections beyond 4 chunks, and those of them hidden. */
	private static long farQuads, farHidden;

	private static List<SectionRenderDispatcher.RenderSection> visible = List.of();
	private static double cameraX, cameraY, cameraZ;
	private static final Matrix4f viewProjection = new Matrix4f();
	private static FloatBuffer depth;
	private static int depthWidth, depthHeight;
	private static java.awt.image.@org.jspecify.annotations.Nullable BufferedImage image;
	/** Screen rectangle of the last box tested. */
	private static int rectX0, rectY0, rectX1, rectY1;

	static {
		for (int i = 0; i < NAMES.length; i++) {
			RenderType type = LAYERS.get(i);
			NAMES[i] = type == RenderType.solid() ? "solid" : type == RenderType.cutoutMipped() ? "cutout_mipped" : type == RenderType.cutout() ? "cutout"
					: type == RenderType.translucent() ? "translucent" : type == RenderType.tripwire() ? "tripwire" : "layer " + i;
		}
	}

	private OcclusionProbe() {
	}

	public static void reset() {
		frames = probes = sections = hiddenSections = probeNanos = farQuads = farHidden = 0;
		java.util.Arrays.fill(quads, 0);
		java.util.Arrays.fill(hiddenQuads, 0);
	}

	/** Called when the frame's chunk draws are made. */
	public static void prepare(List<SectionRenderDispatcher.RenderSection> visibleSections, double camX, double camY, double camZ, Matrix4f projection,
			Matrix4f modelView) {
		visible = visibleSections;
		cameraX = camX;
		cameraY = camY;
		cameraZ = camZ;
		viewProjection.set(projection).mul(modelView);
	}

	/** Called right after the last opaque layer is drawn, with its framebuffer still bound. */
	public static void afterOpaque() {
		if (!measuring || frames++ % EVERY != 0) return;
		long start = System.nanoTime();
		var target = Minecraft.getInstance().getMainRenderTarget();
		int w = target.width, h = target.height;
		if (depth == null || depth.capacity() < w * h) {
			if (depth != null) MemoryUtil.memFree(depth);
			depth = MemoryUtil.memAllocFloat(w * h);
		}
		depthWidth = w;
		depthHeight = h;
		int readBefore = GL33C.glGetInteger(GL33C.GL_READ_FRAMEBUFFER_BINDING);
		int packBefore = GL33C.glGetInteger(GL33C.GL_PIXEL_PACK_BUFFER_BINDING);
		GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, GL33C.glGetInteger(GL33C.GL_DRAW_FRAMEBUFFER_BINDING));
		GL33C.glBindBuffer(GL33C.GL_PIXEL_PACK_BUFFER, 0);
		depth.clear();
		GL33C.glReadPixels(0, 0, w, h, GL33C.GL_DEPTH_COMPONENT, GL33C.GL_FLOAT, depth);
		image = IMAGE && probes == IMAGE_AT ? colorImage(w, h) : null;
		GL33C.glBindBuffer(GL33C.GL_PIXEL_PACK_BUFFER, packBefore);
		GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, readBefore);
		test();
		if (image != null) saveImage();
		probes++;
		probeNanos += System.nanoTime() - start;
	}

	private static void test() {
		Vector4f v = new Vector4f();
		for (SectionRenderDispatcher.RenderSection section : visible) {
			long[] count = new long[LAYERS.size()];
			long total = 0;
			for (int i = 0; i < count.length; i++) total += count[i] = indices(section, i) / 6;
			if (total == 0) continue;
			BlockPos origin = section.getOrigin();
			boolean hidden = hidden(v, (float) (origin.getX() - cameraX), (float) (origin.getY() - cameraY), (float) (origin.getZ() - cameraZ));
			double dx = origin.getX() + 8 - cameraX, dz = origin.getZ() + 8 - cameraZ;
			boolean far = dx * dx + dz * dz > 64.0 * 64.0;
			sections++;
			if (hidden) hiddenSections++;
			if (hidden && image != null) outline(far ? 0xFF2020 : 0x20A0FF);
			for (int i = 0; i < count.length; i++) {
				quads[i] += count[i];
				if (hidden) hiddenQuads[i] += count[i];
			}
			if (far) {
				farQuads += total;
				if (hidden) farHidden += total;
			}
		}
	}

	/** The indices the section's layer draws. */
	private static int indices(SectionRenderDispatcher.RenderSection section, int layer) {
		RenderType type = LAYERS.get(layer);
		if (section.getCompiled().isEmpty(type)) return 0;
		int inRegion = ChunkBatcher.regionIndices(section, layer);
		return inRegion >= 0 ? inRegion : ((VertexBufferAccessor) section.getBuffer(type)).afterburner$indexCount();
	}

	/** Whether the 16-block box at (x, y, z) from the camera is behind what's already in the depth buffer everywhere it covers. */
	private static boolean hidden(Vector4f v, float x, float y, float z) {
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, nearest = Float.MAX_VALUE;
		for (int corner = 0; corner < 8; corner++) {
			float cx = (corner & 1) == 0 ? x - GROW : x + 16 + GROW;
			float cy = (corner & 2) == 0 ? y - GROW : y + 16 + GROW;
			float cz = (corner & 4) == 0 ? z - GROW : z + 16 + GROW;
			viewProjection.transform(v.set(cx, cy, cz, 1.0F));
			// A corner behind the camera or very near it: the box can't be tested on screen, so it counts as seen.
			if (v.w < 0.1F) return false;
			float sx = v.x / v.w, sy = v.y / v.w, sz = v.z / v.w;
			minX = Math.min(minX, sx);
			maxX = Math.max(maxX, sx);
			minY = Math.min(minY, sy);
			maxY = Math.max(maxY, sy);
			// Depth from 0 (near) to 1 (far).
			nearest = Math.min(nearest, sz * 0.5F + 0.5F);
		}
		int x0 = Math.max(0, (int) Math.floor((minX * 0.5F + 0.5F) * depthWidth));
		int x1 = Math.min(depthWidth - 1, (int) Math.ceil((maxX * 0.5F + 0.5F) * depthWidth));
		int y0 = Math.max(0, (int) Math.floor((minY * 0.5F + 0.5F) * depthHeight));
		int y1 = Math.min(depthHeight - 1, (int) Math.ceil((maxY * 0.5F + 0.5F) * depthHeight));
		// Off screen entirely (the frustum test is a little looser than the screen): nothing to compare against.
		if (x0 > x1 || y0 > y1) return false;
		rectX0 = x0;
		rectY0 = y0;
		rectX1 = x1;
		rectY1 = y1;
		for (int py = y0; py <= y1; py++) {
			int row = py * depthWidth;
			for (int px = x0; px <= x1; px++) {
				if (depth.get(row + px) >= nearest) return false;
			}
		}
		return true;
	}

	private static java.awt.image.BufferedImage colorImage(int w, int h) {
		var pixels = MemoryUtil.memAlloc(w * h * 4);
		GL33C.glReadPixels(0, 0, w, h, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels);
		var out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = (y * w + x) * 4;
				int rgb = (pixels.get(i) & 0xFF) << 16 | (pixels.get(i + 1) & 0xFF) << 8 | pixels.get(i + 2) & 0xFF;
				// Darkened, so the outlines stand out; GL rows go bottom to top.
				out.setRGB(x, h - 1 - y, rgb >> 1 & 0x7F7F7F);
			}
		}
		MemoryUtil.memFree(pixels);
		return out;
	}

	private static void outline(int rgb) {
		int h = image.getHeight();
		for (int x = rectX0; x <= rectX1; x++) {
			image.setRGB(x, h - 1 - rectY0, rgb);
			image.setRGB(x, h - 1 - rectY1, rgb);
		}
		for (int y = rectY0; y <= rectY1; y++) {
			image.setRGB(rectX0, h - 1 - y, rgb);
			image.setRGB(rectX1, h - 1 - y, rgb);
		}
	}

	private static void saveImage() {
		try {
			var file = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("afterburner/occlusion-probe.png");
			javax.imageio.ImageIO.write(image, "png", file.toFile());
		} catch (java.io.IOException | RuntimeException e) {
			com.afterburner.Afterburner.LOGGER.warn("Occlusion probe: couldn't save the picture", e);
		}
		image = null;
	}

	public static List<String> lines() {
		if (probes == 0) return List.of();
		long all = 0, hiddenAll = 0;
		for (int i = 0; i < quads.length; i++) {
			all += quads[i];
			hiddenAll += hiddenQuads[i];
		}
		StringBuilder layers = new StringBuilder();
		for (int i = 0; i < quads.length; i++) {
			layers.append(i == 0 ? "" : ", ").append(NAMES[i]).append(' ').append(Reports.f1(percent(hiddenQuads[i], quads[i]))).append('%');
		}
		return List.of(
				"Occlusion probe (" + probes + " looks, " + Reports.f1(probeNanos / 1e6 / probes) + " ms each): " + Reports.f1(percent(hiddenAll, all))
						+ "% of terrain quads and " + Reports.f1(percent(hiddenSections, sections)) + "% of sections drawn were completely hidden ("
						+ layers + ")",
				"Occlusion probe beyond 4 chunks: " + Reports.f1(percent(farHidden, farQuads)) + "% hidden, " + Reports.f1(percent(farQuads, all))
						+ "% of all quads are that far; " + Reports.f0((double) all / probes) + " quads per look");
	}

	private static double percent(long part, long whole) {
		return whole == 0 ? 0 : 100.0 * part / whole;
	}
}
