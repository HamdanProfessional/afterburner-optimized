package com.afterburner.client.render;

import com.afterburner.Afterburner;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * The occlusion test of {@link OcclusionCulling} for graphics backends without compute shaders (Vulkan, or OpenGL
 * drivers without them): the depth pyramid and the box tests are drawn as full-screen passes, and the answers come back
 * from a small texture a frame or two later.
 * <p>
 * The test needs the depth of the solid terrain alone, which only exists in the middle of the main render pass, so that
 * pass is ended there and started again right after, keeping what it drew ({@link RestartablePass}).
 */
final class OcclusionPortable {
	/** Pyramid levels; a box bigger than 2 x 2 texels of the last one (about 500 pixels) always counts as seen. */
	static final int LEVELS = 8;
	private static final int RESULT_WIDTH = 256, RING = 3;
	/** Texels the box texture buffer may hold: the least every device allows. */
	private static final int MAX_TEXELS = 65536;

	private static final RenderPipeline PYRAMID = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("afterburner", "pipeline/occlusion_pyramid"))
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath("afterburner", "core/occlusion_pyramid"))
			.withBindGroupLayout(BindGroupLayout.builder().withUniform("Source", UniformType.COMBINED_IMAGE_SAMPLER).build())
			.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, 15))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build();
	private static final RenderPipeline TEST;

	static {
		BindGroupLayout.Builder layout = BindGroupLayout.builder()
				.withUniform("OcclusionInfo", UniformType.UNIFORM_BUFFER)
				.withUniform("OcclusionBoxes", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT);
		for (int i = 0; i < LEVELS; i++) layout.withUniform("Level" + i, UniformType.COMBINED_IMAGE_SAMPLER);
		TEST = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("afterburner", "pipeline/occlusion_test"))
				.withVertexShader("core/screenquad")
				.withFragmentShader(Identifier.fromNamespaceAndPath("afterburner", "core/occlusion_test"))
				.withBindGroupLayout(layout.build())
				.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, 15))
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.build();
	}

	private static final GpuTexture[] levels = new GpuTexture[LEVELS];
	private static final GpuTextureView[] levelViews = new GpuTextureView[LEVELS];
	private static int sourceWidth, sourceHeight, levelCount;
	private static @Nullable GpuTexture results;
	private static @Nullable GpuTextureView resultsView;
	private static int resultRows;
	private static @Nullable GpuBuffer boxBuffer, infoBuffer;
	private static ByteBuffer boxData = MemoryUtil.memAlloc(64 * 1024);
	private static final ByteBuffer info = MemoryUtil.memAlloc(96);
	private static final Readback[] readbacks = new Readback[RING];

	static {
		for (int i = 0; i < RING; i++) readbacks[i] = new Readback();
	}

	private OcclusionPortable() {
	}

	static final class Readback {
		@Nullable GpuBuffer buffer;
		/** Copy queued, and done (set from the graphics backend's callback, on the render thread). */
		boolean waiting, done;
		long frame;
		/** A copy: the frame's own array is filled again when the frame is rebuilt. */
		SectionRenderDispatcher.RenderSection[] sections = new SectionRenderDispatcher.RenderSection[0];
		int count;
		Object[] owners = new Object[0];
		double[] positions = new double[0];
		int boxCount;
	}

	/**
	 * Tests the sections (and the entity boxes taken) against the depth drawn so far in this pass, which goes on
	 * afterwards. Returns false if it couldn't.
	 */
	static boolean test(RenderPass pass, SectionRenderDispatcher.RenderSection[] sections, int count, ByteBuffer tests, int boxes, ByteBuffer boxBytes,
			Matrix4f viewProjection, boolean zeroToOne) {
		if (!(pass instanceof RestartablePass restartable) || restartable.afterburner$descriptor() == null) return OcclusionCulling.skip("the render pass can't be restarted");
		RenderPassDescriptor descriptor = restartable.afterburner$descriptor();
		RenderPassDescriptor.Attachment<?> depthAttachment = descriptor.depthAttachment();
		if (depthAttachment == null) return OcclusionCulling.skip("the terrain isn't drawn with a depth texture");
		GpuTextureView depth = depthAttachment.textureView();
		GpuTexture depthTexture = depth.texture();
		if ((depthTexture.usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0 || depthTexture.getFormat() != GpuFormat.D32_FLOAT && depthTexture.getFormat() != GpuFormat.D16_UNORM) {
			return OcclusionCulling.skip("the depth texture can't be read by shaders (" + depthTexture.getFormat() + ")");
		}
		int width = depth.getWidth(0), height = depth.getHeight(0);
		RenderPass.RenderArea area = descriptor.renderArea();
		if (area.x() != 0 || area.y() != 0 || area.width() != width || area.height() != height) return OcclusionCulling.skip("the render pass doesn't cover the depth texture");
		if ((count + boxes) * 2L > MAX_TEXELS) {
			boxes = Math.max(0, MAX_TEXELS / 2 - count);
			if (count * 2L > MAX_TEXELS) return OcclusionCulling.skip("too many sections for one test");
		}
		Readback free = null;
		for (Readback r : readbacks) {
			if (!r.waiting) {
				free = r;
				break;
			}
		}
		if (free == null) return false;

		int total = count + boxes;
		int rows = (total + RESULT_WIDTH - 1) / RESULT_WIDTH;
		long texelBytes = (long) total * 32;
		boxData = room(boxData, texelBytes);
		MemoryUtil.memCopy(MemoryUtil.memAddress(tests), MemoryUtil.memAddress(boxData), (long) count * 32);
		if (boxes > 0) MemoryUtil.memCopy(MemoryUtil.memAddress(boxBytes), MemoryUtil.memAddress(boxData) + (long) count * 32, (long) boxes * 32);
		boxData.limit((int) texelBytes).position(0);
		viewProjection.get(0, info);
		info.putInt(64, width).putInt(68, height).putInt(72, 0).putInt(76, zeroToOne ? 1 : 0);
		// Answers are used a frame or two later: room for the camera moving meanwhile (about 4 pixels at 720p), and a box
		// partly off the screen counts as seen.
		info.putInt(80, count).putInt(84, boxes).putInt(88, Math.max(2, (height + 179) / 180)).putInt(92, 1);

		Readback readback = free;
		GpuDevice device = RenderSystem.getDevice();
		boolean[] ran = {false};
		boolean restarted = restartable.afterburner$restart(() -> {
			CommandEncoder encoder = device.createCommandEncoder();
			prepare(device, width, height, rows, texelBytes, readback);
			info.putInt(72, levelCount);
			encoder.writeToBuffer(infoBuffer.slice(0, 96), info.position(0).limit(96));
			encoder.writeToBuffer(boxBuffer.slice(0, texelBytes), boxData);
			GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			GpuTextureView source = depth;
			for (int level = 0; level < levelCount; level++) {
				try (RenderPass p = encoder.createRenderPass(() -> "Afterburner occlusion pyramid", levelViews[level], Optional.empty())) {
					p.setPipeline(RenderSystem.getCompiledPipeline(PYRAMID));
					p.setUniform("Source", source, nearest);
					p.draw(3, 1, 0, 0);
				}
				source = levelViews[level];
			}
			try (RenderPass p = encoder.createRenderPass(() -> "Afterburner occlusion test", resultsView, Optional.empty())) {
				p.setPipeline(RenderSystem.getCompiledPipeline(TEST));
				p.setUniform("OcclusionInfo", infoBuffer.slice(0, 96));
				p.setUniform("OcclusionBoxes", boxBuffer.slice(0, texelBytes));
				for (int i = 0; i < LEVELS; i++) p.setUniform("Level" + i, levelViews[Math.min(i, levelCount - 1)], nearest);
				p.draw(3, 1, 0, 0);
			}
			readback.waiting = true;
			readback.done = false;
			encoder.copyTextureToBuffer(results, readback.buffer, 0L, () -> readback.done = true, 0, 0, 0, RESULT_WIDTH, rows);
			ran[0] = true;
		});
		boxData.clear();
		info.clear();
		if (!restarted) return OcclusionCulling.skip("the render pass can't be restarted here");
		if (!ran[0]) return false;
		readback.frame = OcclusionCulling.frame();
		if (readback.sections.length < count) readback.sections = new SectionRenderDispatcher.RenderSection[Math.max(count, readback.sections.length * 2)];
		System.arraycopy(sections, 0, readback.sections, 0, count);
		readback.count = count;
		if (readback.owners.length < boxes) {
			readback.owners = new Object[Math.max(boxes, readback.owners.length * 2)];
			readback.positions = new double[readback.owners.length * 3];
		}
		System.arraycopy(EntityCulling.owners(), 0, readback.owners, 0, boxes);
		System.arraycopy(EntityCulling.positions(), 0, readback.positions, 0, boxes * 3);
		readback.boxCount = boxes;
		return true;
	}

	/** Textures and buffers for this screen size and this many tests. */
	private static void prepare(GpuDevice device, int width, int height, int rows, long texelBytes, Readback readback) {
		if (width != sourceWidth || height != sourceHeight) {
			for (int i = 0; i < LEVELS; i++) {
				if (levelViews[i] != null) levelViews[i].close();
				if (levels[i] != null) levels[i].close();
				levelViews[i] = null;
				levels[i] = null;
			}
			sourceWidth = width;
			sourceHeight = height;
			int w = width, h = height;
			levelCount = 0;
			while (levelCount < LEVELS && (levelCount == 0 || w > 1 || h > 1)) {
				w = (w + 1) / 2;
				h = (h + 1) / 2;
				levels[levelCount] = device.createTexture(() -> "Afterburner occlusion pyramid", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
						GpuFormat.R32_FLOAT, w, h, 1, 1);
				levelViews[levelCount] = device.createTextureView(levels[levelCount]);
				levelCount++;
			}
		}
		if (results == null || rows > resultRows) {
			if (resultsView != null) resultsView.close();
			if (results != null) results.close();
			resultRows = Math.max(rows, resultRows * 3 / 2);
			results = device.createTexture(() -> "Afterburner occlusion results", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
					GpuFormat.R8_UNORM, RESULT_WIDTH, resultRows, 1, 1);
			resultsView = device.createTextureView(results);
		}
		if (boxBuffer == null || boxBuffer.size() < texelBytes) {
			if (boxBuffer != null) boxBuffer.close();
			boxBuffer = device.createBuffer(() -> "Afterburner occlusion boxes", GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST,
					Math.max(texelBytes * 3 / 2, 64 * 1024));
		}
		if (infoBuffer == null) {
			infoBuffer = device.createBuffer(() -> "Afterburner occlusion info", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 256);
		}
		long resultBytes = (long) RESULT_WIDTH * resultRows;
		if (readback.buffer == null || readback.buffer.size() < resultBytes) {
			if (readback.buffer != null) readback.buffer.close();
			readback.buffer = device.createBuffer(() -> "Afterburner occlusion answers", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, resultBytes);
		}
	}

	private static ByteBuffer room(ByteBuffer buffer, long bytes) {
		if (buffer.capacity() >= bytes) return buffer;
		MemoryUtil.memFree(buffer);
		return MemoryUtil.memAlloc((int) Math.max(bytes, buffer.capacity() * 2L));
	}

	/** The finished read-back with the oldest test, if any. */
	static @Nullable Readback oldestDone() {
		Readback oldest = null;
		for (Readback r : readbacks) {
			if (r.waiting && (oldest == null || r.frame < oldest.frame)) oldest = r;
		}
		return oldest != null && oldest.done ? oldest : null;
	}

	/** Whether the test of index i (sections first, then boxes) saw it. */
	static boolean seen(ByteBuffer answers, int i) {
		return (answers.get(i) & 0xFF) > 127;
	}

	static void release(Readback readback) {
		readback.waiting = readback.done = false;
		java.util.Arrays.fill(readback.sections, 0, readback.count, null);
		java.util.Arrays.fill(readback.owners, 0, readback.boxCount, null);
		readback.boxCount = 0;
	}

	static GpuBufferSlice.MappedView map(Readback readback) {
		return readback.buffer.map(true, false);
	}
}
