package com.afterburner.labs.render;

import com.afterburner.labs.shaderpack.UpscaleShaders;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

/**
 * AMD FidelityFX Super Resolution 1 (FSR 1), written after AMD's published algorithm (MIT licensed): the world is drawn
 * smaller, EASU scales the picture up to the screen, smoothing along the edges it finds instead of across them, and RCAS
 * sharpens the result without letting any pixel overshoot its neighbors. Used for the game's own drawing ({@link WorldScale})
 * and for a shader pack's.
 */
public final class Upscaler implements AutoCloseable {
	/** RCAS's default sharpness, in stops (0 is the sharpest). */
	private static final float SHARPNESS = 0.2F;
	private static final int USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST;

	private static final Identifier VERTEX = Identifier.fromNamespaceAndPath("afterburner", "fsr/fullscreen.vsh");
	private static final Identifier EASU = Identifier.fromNamespaceAndPath("afterburner", "fsr/easu.fsh");
	private static final Identifier RCAS = Identifier.fromNamespaceAndPath("afterburner", "fsr/rcas.fsh");
	private static final Map<Identifier, String> SOURCES = Map.of(VERTEX, UpscaleShaders.VERTEX, EASU, UpscaleShaders.EASU, RCAS, UpscaleShaders.RCAS);
	private static final ShaderSource SHADERS = new ShaderSource() {
		@Override
		public @Nullable String getShader(Identifier id, ShaderType type) {
			return SOURCES.get(id);
		}

		@Override
		public ShaderSource.@Nullable CachedIncludeSource getInclude(Identifier id) {
			return null;
		}

		@Override
		public void close() {
		}
	};

	private final @Nullable CompiledRenderPipeline easu;
	private final @Nullable CompiledRenderPipeline rcas;
	private final GpuBuffer constants;
	private final ByteBuffer data = MemoryUtil.memAlloc(16);
	private final GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
	private final GpuFormat format;
	private @Nullable GpuTexture input;
	private @Nullable GpuTextureView inputView;
	private @Nullable GpuTexture upscaled;
	private @Nullable GpuTextureView upscaledView;
	private int inWidth, inHeight, outWidth, outHeight;

	/** {@code format} is the screen's. */
	public Upscaler(GpuFormat format) {
		this.format = format;
		this.easu = compile("easu", EASU, format);
		this.rcas = compile("rcas", RCAS, format);
		this.constants = RenderSystem.getDevice().createBuffer(() -> "Afterburner upscale constants", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
	}

	private static @Nullable CompiledRenderPipeline compile(String name, Identifier fragment, GpuFormat format) {
		BindGroupLayout layout = BindGroupLayout.builder()
			.withUniform(UpscaleShaders.BLOCK, UniformType.UNIFORM_BUFFER)
			.withUniform(UpscaleShaders.SAMPLER, UniformType.COMBINED_IMAGE_SAMPLER)
			.build();
		try {
			RenderPipeline pipeline = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("afterburner", "fsr/" + name))
				.withVertexShader(VERTEX)
				.withFragmentShader(fragment)
				.withBindGroupLayout(layout)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.withColorTargetState(0, new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
				.build();
			return RenderSystem.getDevice().compilePipeline(pipeline, SHADERS, Util.backgroundExecutor()).join().finishCompile();
		} catch (RuntimeException e) {
			LoggerFactory.getLogger("afterburner").warn("[Afterburner] FSR 1 {} didn't compile", name, e);
			return null;
		}
	}

	/** Whether both programs compiled. */
	public boolean works() {
		return this.easu != null && this.rcas != null;
	}

	/** A texture of the drawn size and the screen's format, kept: what a shader pack's final pass draws into instead of the screen. */
	public GpuTextureView input(int width, int height) {
		if (this.inputView == null || width != this.inWidth || height != this.inHeight) {
			this.closeInput();
			GpuDevice device = RenderSystem.getDevice();
			this.input = device.createTexture("Afterburner upscale input", USAGE, this.format, width, height, 1, 1);
			this.inputView = device.createTextureView(this.input);
			this.inWidth = width;
			this.inHeight = height;
		}
		return this.inputView;
	}

	/** Scales {@code source} (smaller than the screen) up into {@code screen}, then sharpens it. */
	public void run(GpuTextureView source, GpuTextureView screen) {
		if (this.easu == null || this.rcas == null) return;
		int width = screen.getWidth(0);
		int height = screen.getHeight(0);
		if (this.upscaledView == null || width != this.outWidth || height != this.outHeight) {
			this.closeUpscaled();
			GpuDevice device = RenderSystem.getDevice();
			this.upscaled = device.createTexture("Afterburner upscaled", USAGE, this.format, width, height, 1, 1);
			this.upscaledView = device.createTextureView(this.upscaled);
			this.outWidth = width;
			this.outHeight = height;
		}
		this.data.putFloat(0, width).putFloat(4, height).putFloat(8, (float) Math.pow(2.0, -SHARPNESS)).putFloat(12, 0.0F);
		RenderSystem.getDevice().createCommandEncoder().writeToBuffer(this.constants.slice(), this.data);
		this.pass("Afterburner FSR 1 upscale", this.easu, source, this.upscaledView);
		this.pass("Afterburner FSR 1 sharpen", this.rcas, this.upscaledView, screen);
	}

	private void pass(String label, CompiledRenderPipeline pipeline, GpuTextureView source, GpuTextureView target) {
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> label, target, Optional.empty())) {
			pass.setPipeline(pipeline);
			pass.setUniform(UpscaleShaders.BLOCK, this.constants);
			pass.setUniform(UpscaleShaders.SAMPLER, source, this.nearest);
			pass.draw(3, 1, 0, 0);
		}
	}

	private void closeInput() {
		if (this.inputView != null) this.inputView.close();
		if (this.input != null) this.input.close();
		this.inputView = null;
		this.input = null;
	}

	private void closeUpscaled() {
		if (this.upscaledView != null) this.upscaledView.close();
		if (this.upscaled != null) this.upscaled.close();
		this.upscaledView = null;
		this.upscaled = null;
	}

	@Override
	public void close() {
		this.closeInput();
		this.closeUpscaled();
		if (this.easu != null) this.easu.close();
		if (this.rcas != null) this.rcas.close();
		this.constants.close();
		MemoryUtil.memFree(this.data);
	}
}
