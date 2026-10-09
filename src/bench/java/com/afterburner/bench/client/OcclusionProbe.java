package com.afterburner.bench.client;

import com.afterburner.bench.Reports;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.List;

/**
 * Measures how much of the terrain drawn is completely hidden behind nearer terrain, to see whether occlusion culling
 * would be worth it. Only with {@code -Dafterburner.occlusionProbe=true} (benchmarks; OpenGL only).
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
	private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();
	/** With {@code -Dafterburner.occlusionProbeImage=true}, one look is saved as a picture with the hidden sections outlined (checks the math). */
	private static final boolean IMAGE = Boolean.getBoolean("afterburner.occlusionProbeImage");
	private static final int IMAGE_AT = 40;

	/** Whether the frames are counted (during the benchmark's measuring). */
	public static boolean measuring;
	private static long frames, probes, sections, hiddenSections, probeNanos;
	private static final long[] quads = new long[LAYERS.length], hiddenQuads = new long[LAYERS.length];
	/** Quads in sections beyond 4 chunks, and those of them hidden. */
	private static long farQuads, farHidden;

	private static List<SectionRenderDispatcher.RenderSection> visible = List.of();
	private static Vec3 camera = Vec3.ZERO;
	private static final Matrix4f viewProjection = new Matrix4f();
	private static FloatBuffer depth;
	private static int depthWidth, depthHeight;
	private static java.awt.image.@org.jspecify.annotations.Nullable BufferedImage image;
	/** Screen rectangle of the last box tested. */
	private static int rectX0, rectY0, rectX1, rectY1;

	private OcclusionProbe() {
	}

	public static void reset() {
		frames = probes = sections = hiddenSections = probeNanos = farQuads = farHidden = 0;
		java.util.Arrays.fill(quads, 0);
		java.util.Arrays.fill(hiddenQuads, 0);
	}

	/** Called when the frame's chunk draws are made. */
	public static void prepare(List<SectionRenderDispatcher.RenderSection> visibleSections, Vec3 cameraPos, Matrix4f projection, Matrix4f viewRotation) {
		visible = visibleSections;
		camera = cameraPos;
		viewProjection.set(projection).mul(viewRotation);
	}

	/** Called right after the last opaque layer is drawn, with its framebuffer still bound. */
	public static void afterOpaque() {
		if (!measuring || frames++ % EVERY != 0) return;
		if (!RenderSystem.getDevice().getDeviceInfo().backendName().toLowerCase().contains("gl")) return;
		long start = System.nanoTime();
		var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
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
		test(RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
		if (image != null) saveImage();
		probes++;
		probeNanos += System.nanoTime() - start;
	}

	private static void test(boolean zeroToOne) {
		Vector4f v = new Vector4f();
		for (SectionRenderDispatcher.RenderSection section : visible) {
			SectionMesh sectionMesh = section.getSectionMesh();
			if (!(sectionMesh instanceof CompiledSectionMesh mesh)) continue;
			long[] count = new long[LAYERS.length];
			long total = 0;
			for (ChunkSectionLayer layer : LAYERS) {
				SectionMesh.SectionDraw draw = mesh.getSectionDraw(layer);
				if (draw != null) total += count[layer.ordinal()] = draw.indexCount() / 6;
			}
			if (total == 0) continue;
			BlockPos origin = section.getRenderOrigin();
			boolean hidden = hidden(v, (float) (origin.getX() - camera.x), (float) (origin.getY() - camera.y), (float) (origin.getZ() - camera.z), zeroToOne);
			double dx = origin.getX() + 8 - camera.x, dz = origin.getZ() + 8 - camera.z;
			boolean far = dx * dx + dz * dz > 64.0 * 64.0;
			sections++;
			if (hidden) hiddenSections++;
			if (hidden && image != null) outline(far ? 0xFF2020 : 0x20A0FF);
			for (int i = 0; i < LAYERS.length; i++) {
				quads[i] += count[i];
				if (hidden) hiddenQuads[i] += count[i];
			}
			if (far) {
				farQuads += total;
				if (hidden) farHidden += total;
			}
		}
	}

	/** Whether the 16-block box at (x, y, z) from the camera is behind what's already in the depth buffer everywhere it covers. */
	private static boolean hidden(Vector4f v, float x, float y, float z, boolean zeroToOne) {
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, nearest = -Float.MAX_VALUE;
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
			// Reverse depth: bigger is nearer.
			nearest = Math.max(nearest, zeroToOne ? sz : sz * 0.5F + 0.5F);
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
				if (depth.get(row + px) <= nearest) return false;
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
		for (int i = 0; i < LAYERS.length; i++) {
			all += quads[i];
			hiddenAll += hiddenQuads[i];
		}
		StringBuilder layers = new StringBuilder();
		for (int i = 0; i < LAYERS.length; i++) {
			layers.append(i == 0 ? "" : ", ").append(LAYERS[i].label()).append(' ').append(Reports.f1(percent(hiddenQuads[i], quads[i]))).append('%');
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
