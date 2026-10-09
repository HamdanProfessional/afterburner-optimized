package com.afterburner.labs.shaderpack.game;

import com.afterburner.labs.shaderpack.AlphaTest;
import com.afterburner.labs.shaderpack.GlslTranslator;
import com.afterburner.labs.shaderpack.PackBlending;
import com.afterburner.labs.shaderpack.PackLoader;
import com.afterburner.labs.shaderpack.TranslateTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pack's programs as the game's pipelines. A world draw keeps the game's vertex format, topology, culling and blending,
 * and gets the pack's shaders, the pack's buffers as color targets, and the depth test turned around for the normal depth.
 * <p>
 * World pipelines are made when first drawn, without stopping the game: the shaders are turned into the driver's GLSL on a
 * background thread and linked by {@link ProgramLinker}'s, and until then what they draw is left out (a new kind of mob or
 * particle shows a few frames late). Finishing one on the render thread is cheap then, and at most {@link #FINISH_BUDGET}
 * of a frame goes to it, past the first one each frame.
 */
final class PackPipelines implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger("afterburner");

	/** Render thread time per frame for finishing world pipelines, in nanoseconds. */
	private static final long FINISH_BUDGET = 3_000_000L;
	/** Our pipelines' numbers, never reused: a pack loaded again mustn't get the programs of the one before. */
	private static final AtomicInteger NEXT = new AtomicInteger();

	/** A compiled fullscreen program. */
	record Fullscreen(String name, CompiledRenderPipeline pipeline, int[] buffers, Set<String> samplers) {}

	/** A world pipeline: being made, then finished (null if the pack doesn't draw it or it didn't compile). */
	private static final class World {
		final String label;
		final String location;
		final @Nullable CompletableFuture<CompiledRenderPipeline.Pending> pending;
		@Nullable CompiledRenderPipeline pipeline;
		boolean done;

		World(String label, String location, @Nullable CompletableFuture<CompiledRenderPipeline.Pending> pending) {
			this.label = label;
			this.location = location;
			this.pending = pending;
			this.done = pending == null;
		}
	}

	/** What the pack doesn't draw. */
	private static final World NONE = new World("", "", null);

	private final PackLoader.Loaded pack;
	private final PackTargets targets;
	private final @Nullable ShadowMap shadow;
	private final Map<PackPass.Kind, Map<RenderPipeline, World>> world = new EnumMap<>(PackPass.Kind.class);
	private final Set<CompiledRenderPipeline> ours = new ReferenceOpenHashSet<>();
	private final List<CompiledRenderPipeline> compiled = new ArrayList<>();
	/** Read by the background threads making pipelines. */
	private final Map<Identifier, String> sources = new ConcurrentHashMap<>();
	private final Set<String> failed = new LinkedHashSet<>();
	private long finishing;
	/** A shadow draw was left out this frame, its program not ready yet. */
	private boolean shadowWaited;

	private final ShaderSource shaderSource = new ShaderSource() {
		@Override
		public @Nullable String getShader(Identifier id, ShaderType type) {
			return PackPipelines.this.sources.get(id);
		}

		@Override
		public ShaderSource.@Nullable CachedIncludeSource getInclude(Identifier id) {
			return null;
		}

		@Override
		public void close() {
		}
	};

	/** {@code shadow} is null when the shadow map isn't drawn: then nothing is drawn in a shadow pass. */
	PackPipelines(PackLoader.Loaded pack, PackTargets targets, @Nullable ShadowMap shadow) {
		this.pack = pack;
		this.targets = targets;
		this.shadow = shadow;
		for (PackPass.Kind kind : PackPass.Kind.values()) this.world.put(kind, new Reference2ObjectOpenHashMap<>());
	}

	/** The buffers a world pass of {@code kind} draws into: its color attachments (the shadow map has its own). */
	static int[] buffers(PackLoader.Loaded pack, PackPass.Kind kind) {
		return switch (kind) {
			case SKY -> pack.skyBuffers;
			case HAND -> pack.handBuffers;
			case TRANSLUCENT -> pack.translucentBuffers;
			case WORLD, SHADOW, SHADOW_DEPTH -> pack.opaqueBuffers;
		};
	}

	boolean isOurs(CompiledRenderPipeline pipeline) {
		return this.ours.contains(pipeline);
	}

	/** Each frame, before anything is drawn. */
	void beginFrame() {
		this.finishing = 0L;
		this.shadowWaited = false;
	}

	/** Whether a shadow draw was left out this frame because its program wasn't ready: the shadow map is missing it. */
	boolean shadowWaited() {
		return this.shadowWaited;
	}

	/**
	 * The pack's version of a game pipeline drawing in a pack pass, or null when the pack doesn't draw it (clouds, ...) or
	 * it isn't ready yet.
	 */
	@Nullable CompiledRenderPipeline world(RenderPipeline vanilla, PackPass.Kind kind) {
		Map<RenderPipeline, World> cache = this.world.get(kind);
		World found = cache.get(vanilla);
		if (found == null) {
			found = this.startWorld(vanilla, kind);
			cache.put(vanilla, found);
		}
		if (found.done) return found.pipeline;
		CompiledRenderPipeline finished = this.finish(found);
		if (finished == null && !found.done && kind == PackPass.Kind.SHADOW) this.shadowWaited = true;
		return finished;
	}

	private @Nullable CompiledRenderPipeline finish(World w) {
		CompletableFuture<CompiledRenderPipeline.Pending> pending = w.pending;
		if (pending == null || !pending.isDone() || !ProgramLinker.ready(w.location) || this.finishing >= FINISH_BUDGET) return null;
		long start = System.nanoTime();
		w.done = true;
		CompiledRenderPipeline compiled;
		try {
			compiled = pending.join().finishCompile();
		} catch (RuntimeException e) {
			this.fail(w.label, String.valueOf(e.getMessage()));
			return null;
		} finally {
			this.finishing += System.nanoTime() - start;
		}
		if (compiled == null) {
			this.fail(w.label, "didn't compile, see the log above");
			return null;
		}
		this.ours.add(compiled);
		this.compiled.add(compiled);
		w.pipeline = compiled;
		return compiled;
	}

	private World startWorld(RenderPipeline vanilla, PackPass.Kind kind) {
		ShadowMap shadow = kind.shadow() ? this.shadow : null;
		if (kind.shadow() && shadow == null) return NONE;
		ColorTargetState vanillaTarget = vanilla.getColorTargetStates().isEmpty() ? null : vanilla.getColorTargetStates().getFirst();
		boolean translucent = vanillaTarget != null && vanillaTarget.blendFunction().isPresent();
		String path = vanilla.getLocation().getPath();
		// With the pack's shadows, entities cast their own (ShadowMap#renderEntities): the game's round one under them is left out.
		if (this.shadow != null && path.endsWith("entity_shadow")) return NONE;
		String name = ProgramMapping.program(path, kind, translucent, hasElement(vanilla, "UV0"));
		if (name == null) return NONE;
		PackLoader.Program program = this.pack.get(name);
		if (program == null) return NONE;

		// What the game's pipeline gives the shaders.
		Map<String, String> inputs = new LinkedHashMap<>();
		for (VertexFormat format : vanilla.getVertexFormatBindings()) {
			if (format == null) continue;
			for (VertexFormatElement e : format.getElements()) inputs.putIfAbsent(e.name(), glslType(e));
		}
		Set<String> blocks = new LinkedHashSet<>(TranslateTarget.PASS_BLOCKS);
		for (BindGroupLayout layout : vanilla.getBindGroupLayouts()) {
			for (BindGroupLayout.UniformDescription u : layout.uniforms()) {
				if (u.type() == UniformType.UNIFORM_BUFFER) blocks.add(u.name());
			}
		}
		int[] attachments = shadow != null ? shadow.buffers() : buffers(this.pack, kind);
		TranslateTarget target = TranslateTarget.world(shadow != null ? TranslateTarget.Kind.SHADOW : TranslateTarget.Kind.GBUFFERS, inputs, blocks,
			kind == PackPass.Kind.HAND, Map.of(), attachments);
		// What the game cuts out (leaves, grass, item edges), OptiFine's alpha test does for the pack; old programs count on it.
		// The pack's own for the program, else where the game cuts out.
		AlphaTest test = this.pack.alphaTests.get(program.name());
		if (test == null) test = ProgramMapping.alphaTest(path, kind, vanilla.getShaderDefines().values().get("ALPHA_CUTOUT"));
		target = target.withAlphaTest(test);
		String label = program.name() + " for " + vanilla.getLocation();
		GlslTranslator.Program translated;
		try {
			translated = this.pack.translate(program, target);
		} catch (GlslTranslator.TranslateException e) {
			this.fail(label, e.getMessage());
			return NONE;
		}

		RenderPipeline.Builder builder = this.builder(label, translated);
		for (int i = 0; i < vanilla.getVertexFormatBindings().size(); i++) {
			VertexFormat format = vanilla.getVertexFormatBinding(i);
			if (format != null) builder.withVertexBinding(i, format);
		}
		builder.withPrimitiveTopology(vanilla.getPrimitiveTopology());
		// The shadow map gets both sides of every face, so a shape casts its shadow even where only its back faces the sun.
		builder.withCull(shadow == null && vanilla.isCull());
		builder.withPolygonMode(vanilla.getPolygonMode());
		Optional<BlendFunction> blend = vanillaTarget == null ? Optional.empty() : vanillaTarget.blendFunction();
		int mask = vanillaTarget == null || kind == PackPass.Kind.SHADOW_DEPTH ? ColorTargetState.WRITE_NONE : vanillaTarget.writeMask();
		for (int a = 0; a < attachments.length; a++) {
			boolean written = false;
			for (int b : translated.drawBuffers) written |= b == attachments[a];
			GpuFormat format = shadow != null ? shadow.format(attachments[a]) : this.targets.format(attachments[a]);
			// The pack's blend directives (those for single buffers name colortex ones, not the shadow map's).
			Optional<BlendFunction> own = this.blend(name, program.name(), shadow != null ? -1 : attachments[a], blend);
			builder.withColorTargetState(a, new ColorTargetState(own, format, written ? mask : ColorTargetState.WRITE_NONE));
		}
		DepthStencilState depth = vanilla.getDepthStencilState();
		if (depth != null) {
			builder.withDepthStencilState(new DepthStencilState(flip(depth.depthTest()), depth.writeDepth(), -depth.depthBiasScaleFactor(),
				-depth.depthBiasConstant()));
		}
		RenderPipeline pipeline;
		try {
			pipeline = builder.build();
		} catch (RuntimeException e) {
			this.fail(label, e.getMessage());
			return NONE;
		}
		return new World(label, pipeline.getLocation().toString(),
			RenderSystem.getDevice().compilePipeline(pipeline, this.shaderSource, Util.backgroundExecutor()));
	}

	/** A prepare, deferred or composite program drawing into {@code buffers}; for final, {@code finalFormat} is the screen's. */
	@Nullable Fullscreen fullscreen(PackLoader.Program program, @Nullable GpuFormat finalFormat) {
		int[] buffers = finalFormat != null ? new int[] {0} : program.drawBuffers();
		List<Integer> kept = new ArrayList<>();
		for (int b : buffers) if (finalFormat != null || this.targets.has(b)) kept.add(b);
		buffers = kept.stream().mapToInt(Integer::intValue).toArray();
		if (buffers.length == 0) return null;
		GlslTranslator.Program translated;
		try {
			translated = this.pack.translate(program, TranslateTarget.fullscreen(buffers));
		} catch (GlslTranslator.TranslateException e) {
			this.fail(program.name(), e.getMessage());
			return null;
		}
		RenderPipeline.Builder builder = this.builder(program.name(), translated);
		builder.withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
		builder.withCull(false);
		for (int a = 0; a < buffers.length; a++) {
			GpuFormat format = finalFormat != null ? finalFormat : this.targets.format(buffers[a]);
			// No blending, unless the pack's directives say so (blend.composite5=ONE ONE ONE ONE adds onto what's there).
			Optional<BlendFunction> blend = this.blend(program.name(), program.name(), finalFormat != null ? -1 : buffers[a], Optional.empty());
			builder.withColorTargetState(a, new ColorTargetState(blend, format, ColorTargetState.WRITE_ALL));
		}
		CompiledRenderPipeline pipeline = this.compile(program.name(), builder);
		return pipeline == null ? null : new Fullscreen(program.name(), pipeline, buffers, translated.samplers);
	}

	/** The pack's blend ({@link PackBlending#blend}) for a program's buffer, else the game's ({@code game}). */
	private Optional<BlendFunction> blend(String slot, String program, int buffer, Optional<BlendFunction> game) {
		Optional<PackBlending.Factors> own = this.pack.blending.blend(slot, program, buffer);
		if (own == null) return game;
		return own.map(f -> new BlendFunction(BlendFactor.valueOf(f.srcColor()), BlendFactor.valueOf(f.dstColor()),
			BlendFactor.valueOf(f.srcAlpha()), BlendFactor.valueOf(f.dstAlpha())));
	}

	private RenderPipeline.Builder builder(String label, GlslTranslator.Program translated) {
		return this.builder(label, translated.vertex, translated.fragment, translated.blocks, translated.samplers);
	}

	private RenderPipeline.Builder builder(String label, String vertexSource, String fragmentSource, Set<String> blocks, Set<String> samplers) {
		int id = NEXT.getAndIncrement();
		Identifier vertex = Identifier.fromNamespaceAndPath("afterburner", "shaderpack/" + id + ".vsh");
		Identifier fragment = Identifier.fromNamespaceAndPath("afterburner", "shaderpack/" + id + ".fsh");
		this.sources.put(vertex, vertexSource);
		this.sources.put(fragment, fragmentSource);
		BindGroupLayout.Builder layout = BindGroupLayout.builder();
		for (String block : blocks) layout.withUniform(block, UniformType.UNIFORM_BUFFER);
		for (String sampler : samplers) {
			// The pack's image samplers are bound by us (see ExternalBindings), not in the game's layout.
			if (ExternalBindings.get(sampler) == null) layout.withUniform(sampler, UniformType.COMBINED_IMAGE_SAMPLER);
		}
		String path = "shaderpack/" + id + "_" + label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
		return RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("afterburner", path))
			.withVertexShader(vertex)
			.withFragmentShader(fragment)
			.withBindGroupLayout(layout.build());
	}

	private @Nullable CompiledRenderPipeline compile(String label, RenderPipeline.Builder builder) {
		RenderPipeline pipeline;
		try {
			pipeline = builder.build();
		} catch (RuntimeException e) {
			this.fail(label, e.getMessage());
			return null;
		}
		CompiledRenderPipeline compiled = RenderSystem.getDevice().compilePipeline(pipeline, this.shaderSource, Util.backgroundExecutor()).join().finishCompile();
		if (compiled == null) {
			// The game logged why (with line numbers of the translated source).
			this.fail(label, "didn't compile, see the log above");
			return null;
		}
		this.ours.add(compiled);
		this.compiled.add(compiled);
		return compiled;
	}

	private void fail(String label, String why) {
		if (this.failed.add(label)) LOGGER.warn("[Afterburner] Shader pack: {}: {}", label, why);
	}

	/** The GLSL type a vertex element is read as: its component kind and count; Normal is a vec3. */
	private static String glslType(VertexFormatElement e) {
		GpuFormat f = e.format();
		int n = e.name().equals("Normal") ? 3 : f.componentCount();
		String prefix = switch (f.componentType()) {
			case UINT_8, UINT_16, UINT_32 -> "u";
			case SINT_8, SINT_16, SINT_32 -> "i";
			default -> "";
		};
		if (n == 1) return prefix.equals("u") ? "uint" : prefix.equals("i") ? "int" : "float";
		return prefix + "vec" + n;
	}

	private static boolean hasElement(RenderPipeline pipeline, String name) {
		for (VertexFormat format : pipeline.getVertexFormatBindings()) {
			if (format != null && format.contains(name)) return true;
		}
		return false;
	}

	/** The game's depth test for reversed depth, as the test for normal depth. */
	private static CompareOp flip(CompareOp op) {
		return switch (op) {
			case LESS_THAN -> CompareOp.GREATER_THAN;
			case LESS_THAN_OR_EQUAL -> CompareOp.GREATER_THAN_OR_EQUAL;
			case GREATER_THAN -> CompareOp.LESS_THAN;
			case GREATER_THAN_OR_EQUAL -> CompareOp.LESS_THAN_OR_EQUAL;
			default -> op;
		};
	}

	@Override
	public void close() {
		for (CompiledRenderPipeline p : this.compiled) p.close();
		this.compiled.clear();
		this.ours.clear();
		for (Map<RenderPipeline, World> cache : this.world.values()) {
			for (World w : cache.values()) {
				if (!w.done) ProgramLinker.discard(w.location);
			}
			cache.clear();
		}
		this.shaderSource.close();
	}
}
