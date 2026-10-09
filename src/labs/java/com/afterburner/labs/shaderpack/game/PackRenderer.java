package com.afterburner.labs.shaderpack.game;

import com.afterburner.Features;
import com.afterburner.labs.lod.Lod;
import com.afterburner.client.render.RestartablePass;
import com.afterburner.labs.render.Upscaler;
import com.afterburner.labs.shaderpack.CustomUniforms;
import com.afterburner.labs.shaderpack.PackFiles;
import com.afterburner.labs.shaderpack.PackLoader;
import com.afterburner.labs.shaderpack.ProgramSet;
import com.afterburner.labs.shaderpack.StandardMacros;
import com.afterburner.labs.shaderpack.UniformLayout;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One loaded pack drawing the world: the frame's uniforms, the buffers, and the fullscreen passes around the game's own world
 * passes, which draw into the pack's buffers (see {@link Shaderpacks#redirect}).
 * <p>
 * A frame: the pack's images cleared, its setup (first frame only) and begin compute programs, the shadow map (or the one from
 * the frame before, kept while nothing it shows changed, with the images it wrote), shadowcomp, then
 * clear and prepare at the start; the sky and the opaque world; deferred before translucents (the main pass is ended and started
 * again around it); translucents; the hand; then composite and final into the game's screen. In each pass group a name's compute
 * programs (composite1_a.csh, ...) run before its fragment program (composite1).
 * <p>
 * With upscaling on, all of it is drawn smaller than the screen, and final draws into a texture that {@link Upscaler} scales up
 * into the screen.
 */
public final class PackRenderer implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger("afterburner");
	/** Distant Horizons' names for the far terrain's depth. */
	private static final String[] FAR_DEPTH = {"dhDepthTex", "dhDepthTex0", "dhDepthTex1"};

	/** A pass name's compute programs (if it has any), then its fragment program (if it has one). */
	private record Step(String name, PackPipelines.@Nullable Fullscreen program) {}

	final String name;
	final PackLoader.Loaded pack;
	final PackTargets targets;
	final PackTextures textures;
	final PackPipelines pipelines;
	/** Null when the pack has no shadow program, or the chunk batcher (which draws it) is off. */
	private final @Nullable ShadowMap shadowMap;
	private final FrameUniforms frame;
	private final GpuBuffer frameBuffer;
	private final GpuBuffer drawBuffer;
	private final ByteBuffer frameData;
	private final CustomUniforms.Inputs inputs;
	private final Map<String, List<Step>> passes = new LinkedHashMap<>();
	/** The pack's images and storage buffers, and its compute programs: null without any, or where they can't run. */
	private final @Nullable PackStorage storage;
	private final @Nullable PackComputes computes;
	/** The draw block for each render stage (renderStage set to its number), or null when the pack doesn't read renderStage. */
	private final GpuBuffer @Nullable [] stageBuffers;
	/** The render stage of each of the game's pipelines, by the kind of pass it draws in. */
	private final Map<PackPass.Kind, Reference2IntOpenHashMap<RenderPipeline>> stages = new EnumMap<>(PackPass.Kind.class);
	/** What went wrong while drawing (an image or buffer that couldn't be made): logged at the end of the frame's start. */
	private final List<String> lateWarnings = new ArrayList<>();
	private boolean setupRun;
	private final PackPipelines.@Nullable Fullscreen finalPass;
	private final GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
	private final GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
	private boolean frameStarted;
	/** Made when upscaling is first on; null until then, or if its programs didn't compile. */
	private @Nullable Upscaler upscaler;
	private boolean upscalerFailed;
	/** This frame is drawn smaller than the screen. */
	private boolean scaled;
	/** The far terrain was drawn this frame (or tried), or it failed and is off. */
	private boolean farTerrainDrawn, farTerrainFailed;
	/** Its water drawn on its own failed and is off (it's drawn with the rest); the floor under it is drawn this frame. */
	private boolean farWaterFailed, farFloorDrawn;

	PackRenderer(String name, PackLoader.Loaded pack, PackFiles files, List<String> warnings) {
		this.name = name;
		this.pack = pack;
		ProgramLinker.start();
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		boolean[] used = new boolean[PackTargets.BUFFERS];
		used[0] = true;
		for (PackLoader.Program p : pack.programs.values()) {
			for (int b : p.drawBuffers()) if (b >= 0 && b < PackTargets.BUFFERS) used[b] = true;
		}
		this.targets = new PackTargets(used, pack.consts, main.width, main.height);
		this.textures = new PackTextures(files, pack.properties, warnings);
		this.frame = new FrameUniforms(pack.consts);
		// The images and storage buffers first: the pack's programs are compiled with them in place.
		boolean storage = !pack.images.isEmpty() || !pack.bufferBlocks.isEmpty();
		boolean extras = storage || !pack.computes.isEmpty();
		boolean runs = extras && PackStorage.supported();
		if (extras && !runs) warnings.add("Images, storage buffers and compute programs need OpenGL 4.3, which this GPU or driver doesn't have: left out");
		this.storage = runs && storage ? new PackStorage(pack, warnings) : null;
		this.computes = runs && !pack.computes.isEmpty() ? new PackComputes(pack, warnings) : null;
		boolean shadow = pack.get("shadow") != null || pack.get("shadow_solid") != null || pack.get("shadow_cutout") != null;
		if (shadow && !Features.CHUNK_BATCHING.active()) warnings.add("No shadows: they're drawn by batched chunk drawing, which is off");
		this.shadowMap = shadow && Features.CHUNK_BATCHING.active()
			? new ShadowMap(pack, this.frame, this.storage != null ? this.storage.volume() : null)
			: null;
		this.pipelines = new PackPipelines(pack, this.targets, this.shadowMap);
		this.inputs = n -> {
			double[] v = pack.customUniforms.value(n);
			if (v != null) return v;
			if (n.equals("alphaTestRef")) return new double[] {0.1};
			return this.frame.get(n);
		};

		var device = RenderSystem.getDevice();
		UniformLayout layout = pack.layout;
		this.frameBuffer = device.createBuffer(() -> "Afterburner shader pack frame", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, layout.frameSize());
		this.drawBuffer = device.createBuffer(() -> "Afterburner shader pack draw", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, layout.drawSize());
		this.frameData = MemoryUtil.memAlloc(Math.max(layout.frameSize(), layout.drawSize()));
		Map<String, double[]> drawDefaults = Map.of("entityId", new double[] {0}, "blockEntityId", new double[] {0},
			"currentRenderedItemId", new double[] {0});
		this.upload(this.drawBuffer, true, drawDefaults::get);
		boolean staged = false;
		for (UniformLayout.Member m : layout.members()) staged |= m.perDraw() && m.name().equals("renderStage");
		if (staged) {
			this.stageBuffers = new GpuBuffer[StandardMacros.RENDER_STAGES.length];
			for (int i = 0; i < this.stageBuffers.length; i++) {
				String stage = StandardMacros.RENDER_STAGES[i];
				double[] value = {i};
				this.stageBuffers[i] = device.createBuffer(() -> "Afterburner shader pack draw, " + stage, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
					layout.drawSize());
				this.upload(this.stageBuffers[i], true, n -> n.equals("renderStage") ? value : drawDefaults.get(n));
			}
		} else {
			this.stageBuffers = null;
		}
		for (PackPass.Kind kind : PackPass.Kind.values()) this.stages.put(kind, new Reference2IntOpenHashMap<>());

		for (String group : ProgramSet.PASS_GROUPS) {
			List<Step> steps = new ArrayList<>();
			for (int i = 0; i < 100; i++) {
				String passName = i == 0 ? group : group + i;
				// shadowcomp's fragment programs aren't drawn, only its compute ones.
				PackLoader.Program program = group.equals("shadowcomp") ? null : pack.programs.get(passName);
				PackPipelines.Fullscreen f = program == null ? null : this.pipelines.fullscreen(program, null);
				if (f != null || this.computes != null && this.computes.has(passName)) steps.add(new Step(passName, f));
			}
			this.passes.put(group, steps);
		}
		PackLoader.Program finalProgram = pack.get("final");
		this.finalPass = finalProgram == null ? null : this.pipelines.fullscreen(finalProgram, main.getColorTexture().getFormat());
	}

	private void upload(GpuBuffer buffer, boolean perDraw, CustomUniforms.Inputs values) {
		int size = perDraw ? this.pack.layout.drawSize() : this.pack.layout.frameSize();
		this.frameData.clear();
		this.pack.layout.write(this.frameData, perDraw, values);
		this.frameData.limit(size);
		RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(0, size), this.frameData);
	}

	// ---- The frame ----

	/** Before the world is drawn: the frame's uniforms, the clears, and the prepare passes. */
	void beginFrame(Minecraft mc, CameraRenderState camera, Matrix4fc projection) {
		this.farTerrainDrawn = false;
		this.farFloorDrawn = false;
		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		float scale = Shaderpacks.upscale().scale;
		this.scaled = scale < 1.0F && this.upscaler() != null;
		int width = this.scaled ? Math.max(1, Math.round(main.width * scale)) : main.width;
		int height = this.scaled ? Math.max(1, Math.round(main.height * scale)) : main.height;
		this.targets.resize(width, height);
		this.pipelines.beginFrame();
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
		this.frame.update(mc, mc.gameRenderer.mainCamera(), partialTick, projection, camera.viewRotationMatrix, zeroToOne, width, height);
		this.pack.customUniforms.update(this.frame);
		this.upload(this.frameBuffer, false, this.inputs);
		// A shadow map kept from the frame before keeps the images its programs wrote.
		boolean keepShadow = this.shadowMap != null && this.shadowMap.holds(mc, this.frame, this.inputs);
		if (this.storage != null) {
			this.storage.resize(width, height, this.lateWarnings);
			this.storage.bind();
			if (this.storage.clearImages(keepShadow ? this.shadowMap.written() : Set.of())) keepShadow = false;
		}
		if (!this.setupRun) {
			this.setupRun = true;
			this.computeGroup("setup");
		}
		this.computeGroup("begin");
		if (this.shadowMap != null) {
			if (keepShadow) this.shadowMap.keep(mc, this.frame, this::bindShadowPass);
			else this.shadowMap.render(mc, this.frame, this.inputs, this::bindShadowPass);
			if (this.pipelines.shadowWaited()) this.shadowMap.invalidate();
		}
		this.run("shadowcomp", "shadowcomp");
		if (!this.lateWarnings.isEmpty()) {
			for (String w : this.lateWarnings) LOGGER.warn("[Afterburner] {}: {}", this.name, w);
			this.lateWarnings.clear();
		}

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		double[] fog = this.frame.get("fogColor");
		this.targets.clear(encoder, fog == null ? new Vector4f(0, 0, 0, 1) : new Vector4f((float) fog[0], (float) fog[1], (float) fog[2], 1));
		this.run("prepare", "prepare");
		this.frameStarted = true;
	}

	/** Called when the game opens a pass: our buffers in place of the game's for the world passes. */
	RenderPassDescriptor redirect(RenderPassDescriptor descriptor, PackPass.Kind kind) {
		// depthtex2 is the depth with everything but the hand.
		if (kind == PackPass.Kind.HAND) this.targets.copyDepth(RenderSystem.getDevice().createCommandEncoder(), true);
		if (kind == PackPass.Kind.WORLD && !this.farTerrainDrawn) {
			this.farTerrainDrawn = true;
			this.drawFarTerrain();
		}
		return this.worldPass(descriptor, kind);
	}

	/** A world pass of {@code kind} on the pack's buffers (see {@link PackPipelines#buffers}) and depth, nothing cleared. */
	private RenderPassDescriptor worldPass(RenderPassDescriptor descriptor, PackPass.Kind kind) {
		List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors = new ArrayList<>();
		for (int b : PackPipelines.buffers(this.pack, kind)) colors.add(new RenderPassDescriptor.Attachment<>(this.targets.read(b), Optional.empty()));
		return new RenderPassDescriptor(descriptor.label(), colors, new RenderPassDescriptor.Attachment<>(this.targets.depth(), OptionalDouble.empty()),
			new RenderPass.RenderArea(0, 0, this.targets.width, this.targets.height));
	}

	/** The kind of the world's passes after the deferred passes: TRANSLUCENT if the pack's world buffers are split, else WORLD. */
	private PackPass.Kind afterDeferred() {
		return this.pack.splitsWorld() ? PackPass.Kind.TRANSLUCENT : PackPass.Kind.WORLD;
	}

	/**
	 * Afterburner's far terrain, with the pack's dh_terrain (as Distant Horizons' is with Iris): before the main pass, into the
	 * world's buffers and a depth of its own (dhDepthTex), so the game's terrain is drawn over it, and the pack's later passes
	 * find it where the game drew nothing.
	 */
	private void drawFarTerrain() {
		if (this.farTerrainFailed || !this.drawsFarTerrain() || !Lod.drawsForPack()) return;
		RenderPipeline source = Lod.packPipeline();
		CompiledRenderPipeline pipeline = this.pipelines.world(source, PackPass.Kind.WORLD);
		if (pipeline == null) return;
		// The water on its own later (drawFarWater), once the pipelines for it are ready; till then with the rest.
		boolean water = this.drawsFarWater() && Lod.hasWaterForPack() && this.farWaterReady();
		List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors = new ArrayList<>();
		for (int b : PackPipelines.buffers(this.pack, PackPass.Kind.WORLD)) {
			colors.add(new RenderPassDescriptor.Attachment<>(this.targets.read(b), Optional.empty()));
		}
		RenderPassDescriptor descriptor = new RenderPassDescriptor(() -> "Afterburner far terrain", colors,
			new RenderPassDescriptor.Attachment<>(this.targets.farDepth(), OptionalDouble.empty()),
			new RenderPass.RenderArea(0, 0, this.targets.width, this.targets.height));
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		try (RenderPass pass = encoder.createRenderPass(descriptor)) {
			RenderSystem.bindDefaultUniforms(pass);
			this.bindWorld(pass, PackPass.Kind.WORLD);
			// It draws into dhDepthTex.
			for (String name : FAR_DEPTH) pass.setUniform(name, this.textures.noShadow.view(), this.textures.noShadow.sampler());
			this.bindStage(pass, source, PackPass.Kind.WORLD, Shaderpacks.SkyStage.NONE);
			Lod.drawForPack(pass, pipeline, water);
		} catch (RuntimeException e) {
			// The far terrain goes, not the pack.
			this.farTerrainFailed = true;
			LOGGER.error("[Afterburner] {}: the far terrain failed, it's off with this pack", this.name, e);
			return;
		}
		// dhDepthTex1: without the water drawn later.
		this.targets.copyFarDepth(encoder, false);
	}

	/** Whether the pipelines for the far water are ready: the pack's dh_water for it, and the floor under it. */
	private boolean farWaterReady() {
		if (this.pipelines.world(Lod.packWaterPipeline(), this.afterDeferred()) == null) return false;
		if (RenderSystem.getCompiledPipelineNullable(Lod.packFloorPipeline()) != null) return true;
		this.farWaterFailed = true;
		LOGGER.error("[Afterburner] {}: the far water's floor shader didn't load; far water is drawn solid with this pack", this.name);
		return false;
	}

	/**
	 * The far terrain's water, with the pack's dh_water, after the deferred passes as the game's translucents are (Iris draws
	 * Distant Horizons' water so). The far terrain keeps no ground under its water, and packs see through water to what's
	 * under it (dhDepthTex1), fading it out over nothing: first a floor's depth under it there (far_water_floor.fsh), then
	 * the water into the world's buffers and dhDepthTex, blended.
	 */
	private void drawFarWater() {
		if (this.farWaterFailed || !Lod.waterPendingForPack()) return;
		CompiledRenderPipeline floor = RenderSystem.getCompiledPipelineNullable(Lod.packFloorPipeline());
		RenderPipeline source = Lod.packWaterPipeline();
		PackPass.Kind kind = this.afterDeferred();
		CompiledRenderPipeline pipeline = this.pipelines.world(source, kind);
		if (floor == null || pipeline == null) return;
		RenderPass.RenderArea area = new RenderPass.RenderArea(0, 0, this.targets.width, this.targets.height);
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		try {
			this.targets.copyFarDepth(encoder, true);
			RenderPassDescriptor floorPass = new RenderPassDescriptor(() -> "Afterburner far water floor", List.of(),
				new RenderPassDescriptor.Attachment<>(this.targets.farDepthFloor(), OptionalDouble.empty()), area);
			try (RenderPass pass = encoder.createRenderPass(floorPass)) {
				RenderSystem.bindDefaultUniforms(pass);
				Lod.drawWaterForPack(pass, floor, false);
			}
			this.farFloorDrawn = true;
			List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors = new ArrayList<>();
			for (int b : PackPipelines.buffers(this.pack, kind)) colors.add(new RenderPassDescriptor.Attachment<>(this.targets.read(b), Optional.empty()));
			RenderPassDescriptor descriptor = new RenderPassDescriptor(() -> "Afterburner far water", colors,
				new RenderPassDescriptor.Attachment<>(this.targets.farDepth(), OptionalDouble.empty()), area);
			try (RenderPass pass = encoder.createRenderPass(descriptor)) {
				RenderSystem.bindDefaultUniforms(pass);
				this.bindWorld(pass, kind);
				// It draws into dhDepthTex: it sees the far terrain's from before it.
				pass.setUniform("dhDepthTex", this.targets.farDepthSolid(), this.nearest);
				pass.setUniform("dhDepthTex0", this.targets.farDepthSolid(), this.nearest);
				this.bindStage(pass, source, kind, Shaderpacks.SkyStage.NONE);
				Lod.drawWaterForPack(pass, pipeline, true);
			}
		} catch (RuntimeException e) {
			// From the next frame it's drawn with the rest.
			this.farWaterFailed = true;
			LOGGER.error("[Afterburner] {}: the far water failed; it's drawn solid with this pack", this.name, e);
		}
	}

	/** Whether the pack draws the far terrain's water on its own, see-through: it has Distant Horizons' water program. */
	boolean drawsFarWater() {
		return !this.farWaterFailed && this.pack.get("dh_water") != null;
	}

	/** dhDepthTex1: the far terrain without its water drawn on its own, and from that on with the floor under it. */
	private GpuTextureView farUnder() {
		return this.farFloorDrawn ? this.targets.farDepthFloor() : this.targets.farDepthSolid();
	}

	/** dhDepthTex and dhDepthTex0 (the far terrain's depth) and dhDepthTex1 ({@link #farUnder}). */
	private void bindFarDepth(RenderPass pass) {
		pass.setUniform("dhDepthTex", this.targets.farDepth(), this.nearest);
		pass.setUniform("dhDepthTex0", this.targets.farDepth(), this.nearest);
		pass.setUniform("dhDepthTex1", this.farUnder(), this.nearest);
	}

	/** Whether the pack draws Afterburner's far terrain: it has Distant Horizons' terrain program. */
	boolean drawsFarTerrain() {
		return !this.farTerrainFailed && this.pack.get("dh_terrain") != null;
	}

	Matrix4fc farTerrainProjection() {
		return this.frame.dhProjection;
	}

	boolean owns(GpuTextureView view) {
		return this.targets.owns(view);
	}

	boolean frameStarted() {
		return this.frameStarted;
	}

	/**
	 * A pipeline was set in a pack pass: the draw block with its render stage. {@code source} is the game's pipeline it draws
	 * for (null if not known); {@code sky} is what the sky is drawing, where its pipeline doesn't tell.
	 */
	void bindStage(RenderPass pass, @Nullable RenderPipeline source, PackPass.Kind kind, Shaderpacks.SkyStage sky) {
		GpuBuffer[] buffers = this.stageBuffers;
		if (buffers == null) return;
		int stage;
		if (kind == PackPass.Kind.SKY && sky != Shaderpacks.SkyStage.NONE) {
			stage = ProgramMapping.stage(sky.stage);
		} else if (source == null) {
			stage = 0;
		} else {
			Reference2IntOpenHashMap<RenderPipeline> cache = this.stages.get(kind);
			stage = cache.getOrDefault(source, -1);
			if (stage < 0) {
				ColorTargetState target = source.getColorTargetStates().isEmpty() ? null : source.getColorTargetStates().getFirst();
				stage = ProgramMapping.stage(source.getLocation().getPath(), kind, target != null && target.blendFunction().isPresent());
				cache.put(source, stage);
			}
		}
		pass.setUniform(UniformLayout.DRAW_BLOCK, buffers[stage]);
	}

	/** A pack world pass of {@code kind} was opened: the pack's uniforms and textures, for all its draws. */
	void bindWorld(RenderPass pass, PackPass.Kind kind) {
		int[] buffers = PackPipelines.buffers(this.pack, kind);
		pass.setUniform(UniformLayout.FRAME_BLOCK, this.frameBuffer);
		pass.setUniform(UniformLayout.DRAW_BLOCK, this.drawBuffer);
		pass.setUniform("Sampler0", this.textures.white.view(), this.textures.white.sampler());
		pass.setUniform("Sampler2", this.textures.white.view(), this.textures.white.sampler());
		for (int b = 0; b < PackTargets.BUFFERS; b++) {
			if (!this.targets.has(b)) continue;
			// The world draws into a buffer's latest texture, so reading it gets the other one.
			boolean drawn = false;
			for (int w : buffers) drawn |= w == b;
			pass.setUniform("colortex" + b, drawn ? this.targets.write(b) : this.targets.read(b), this.linear);
		}
		this.bindCommon(pass, "gbuffers", true);
	}

	/** depthtex, shadow, noise and the stage's own textures. */
	private void bindCommon(RenderPass pass, String stage, boolean world) {
		// The world draws into depthtex0, so it reads the copy.
		pass.setUniform("depthtex0", world ? this.targets.depthNoTranslucents() : this.targets.depth(), this.nearest);
		pass.setUniform("depthtex1", this.targets.depthNoTranslucents(), this.nearest);
		pass.setUniform("depthtex2", this.targets.depthNoHand(), this.nearest);
		this.bindFarDepth(pass);
		this.bindShadowMap(pass, this.shadowMap);
		pass.setUniform("noisetex", this.textures.noise.view(), this.textures.noise.sampler());
		this.bindMaterialMaps(pass);
		for (Map.Entry<String, PackTextures.Bound> e : this.textures.custom(stage).entrySet()) {
			pass.setUniform(e.getKey(), e.getValue().view(), e.getValue().sampler());
		}
	}

	/** The normal and specular maps (normals, specular): flat and blank, as no resource pack gives them yet. */
	private void bindMaterialMaps(RenderPass pass) {
		pass.setUniform("normals", this.textures.flatNormals.view(), this.textures.flatNormals.sampler());
		pass.setUniform("specular", this.textures.black.view(), this.textures.black.sampler());
	}

	/** shadowtex and shadowcolor: the shadow map's, or (without one) all lit and white. */
	private void bindShadowMap(RenderPass pass, @Nullable ShadowMap map) {
		for (int i = 0; i < ShadowMap.COLORS; i++) {
			if (map != null) pass.setUniform("shadowtex" + i, i == 0 ? map.depth() : map.depthNoTranslucents(), this.nearest);
			else pass.setUniform("shadowtex" + i, this.textures.noShadow.view(), this.textures.noShadow.sampler());
			GpuTextureView color = map != null ? map.color(i) : null;
			if (color != null) pass.setUniform("shadowcolor" + i, color, this.linear);
			else pass.setUniform("shadowcolor" + i, this.textures.white.view(), this.textures.white.sampler());
		}
	}

	/** The shadow pass: the pack's uniforms and textures, but not the shadow map it draws. */
	private void bindShadowPass(RenderPass pass) {
		pass.setUniform(UniformLayout.FRAME_BLOCK, this.frameBuffer);
		pass.setUniform(UniformLayout.DRAW_BLOCK, this.drawBuffer);
		for (int b = 0; b < PackTargets.BUFFERS; b++) {
			if (this.targets.has(b)) pass.setUniform("colortex" + b, this.targets.read(b), this.linear);
		}
		pass.setUniform("depthtex0", this.targets.depth(), this.nearest);
		pass.setUniform("depthtex1", this.targets.depthNoTranslucents(), this.nearest);
		pass.setUniform("depthtex2", this.targets.depthNoHand(), this.nearest);
		this.bindFarDepth(pass);
		this.bindShadowMap(pass, null);
		pass.setUniform("noisetex", this.textures.noise.view(), this.textures.noise.sampler());
		this.bindMaterialMaps(pass);
		for (Map.Entry<String, PackTextures.Bound> e : this.textures.custom("shadow").entrySet()) {
			pass.setUniform(e.getKey(), e.getValue().view(), e.getValue().sampler());
		}
	}

	/** How far from the camera entities cast shadows: 0 without a shadow map. */
	double entityShadowRange() {
		return this.shadowMap != null ? this.shadowMap.entityRange() : 0.0;
	}

	/** The game's draws of this frame's entities are prepared: their shadows go into the shadow map. */
	void entityShadows(FeatureRenderDispatcher.PreparedFrame features) {
		if (this.shadowMap != null && this.frameStarted) this.shadowMap.renderEntities(this.frame, features, this::bindShadowPass);
	}

	/**
	 * Before translucents, in the main pass: ends it, copies the depth to depthtex1, runs the deferred passes, draws the far
	 * water, and starts it again on the buffers as they are now.
	 */
	void beforeTranslucents(RenderPass main) {
		if (!(main instanceof RestartablePass restartable)) return;
		// With the world's buffers split, the translucents go on buffers of their own.
		PackPass.Kind after = this.afterDeferred();
		RenderPassDescriptor before = restartable.afterburner$descriptor();
		RenderPassDescriptor next = after != PackPass.Kind.WORLD && before != null ? this.worldPass(before, after) : null;
		boolean restarted = restartable.afterburner$restart(() -> {
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			this.targets.copyDepth(encoder, false);
			this.targets.copyDepth(encoder, true);
			this.run("deferred", "deferred");
			this.drawFarWater();
		}, next);
		if (!restarted) return;
		if (next != null && main instanceof PackPass p) p.afterburner$setPackKind(after);
		this.bindWorld(main, next != null ? after : PackPass.Kind.WORLD);
	}

	/** After the hand: composite, then final into the game's screen. */
	void finish(Minecraft mc) {
		if (!this.frameStarted) return;
		this.frameStarted = false;
		this.run("composite", "composite");
		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		Upscaler upscaler = this.scaled ? this.upscaler : null;
		if (upscaler != null) {
			// No final: colortex0 is the picture to scale up.
			GpuTextureView picture = this.targets.read(0);
			if (this.finalPass != null) {
				picture = upscaler.input(this.targets.width, this.targets.height);
				this.draw(this.finalPass, "composite", picture);
			}
			upscaler.run(picture, main.getColorTextureView());
		} else if (this.finalPass != null) {
			this.draw(this.finalPass, "composite", main.getColorTextureView());
		} else {
			// No final: colortex0 is the picture.
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Afterburner shader pack blit",
					main.getColorTextureView(), Optional.empty())) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("InSampler", this.targets.read(0), this.nearest);
				pass.draw(3, 1, 0, 0);
			}
		}
	}

	/** The upscaler, made on first use; null if its programs didn't compile (then the world is drawn full size). */
	private @Nullable Upscaler upscaler() {
		if (this.upscaler == null && !this.upscalerFailed) {
			Upscaler u = new Upscaler(Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture().getFormat());
			if (u.works()) {
				this.upscaler = u;
			} else {
				u.close();
				this.upscalerFailed = true;
			}
		}
		return this.upscaler;
	}

	private void run(String group, String stage) {
		List<Step> list = this.passes.get(group);
		if (list == null) return;
		for (Step step : list) {
			if (this.computes != null && this.computes.has(step.name)) this.compute(step.name, stage);
			if (step.program == null) continue;
			// What earlier programs wrote to images, this one reads.
			if (this.storage != null) this.storage.barrier();
			this.draw(step.program, stage, null);
			for (int b : step.program.buffers()) this.targets.flip(b);
		}
	}

	/** The compute programs of setup or begin (setup, setup1, ...): groups without fragment programs. */
	private void computeGroup(String group) {
		if (this.computes == null) return;
		for (int i = 0; i < 100; i++) {
			String passName = i == 0 ? group : group + i;
			if (this.computes.has(passName)) this.compute(passName, group);
		}
	}

	private void compute(String passName, String stage) {
		PackComputes c = this.computes;
		if (c == null) return;
		if (this.storage != null) this.storage.barrier();
		c.run(passName, sampler -> this.texture(sampler, stage), this.frameBuffer, this.drawBuffer, this.targets.width, this.targets.height);
	}

	/** What a compute program reads under a sampler name: what a fullscreen program reads under it. */
	private PackTextures.@Nullable Bound texture(String sampler, String stage) {
		PackTextures.Bound custom = this.textures.custom(stage, sampler);
		if (custom != null) return custom;
		if (sampler.startsWith("colortex")) {
			int b = parse(sampler.substring("colortex".length()));
			if (this.targets.has(b)) return new PackTextures.Bound(this.targets.read(b), this.linear);
			return new PackTextures.Bound((b == 1 ? this.textures.white : this.textures.black).view(), this.nearest);
		}
		if (sampler.startsWith("shadowcolor")) {
			GpuTextureView color = this.shadowMap != null ? this.shadowMap.color(parse(sampler.substring("shadowcolor".length()))) : null;
			return color != null ? new PackTextures.Bound(color, this.linear) : this.textures.white;
		}
		return switch (sampler) {
			case "depthtex0" -> new PackTextures.Bound(this.targets.depth(), this.nearest);
			case "depthtex1" -> new PackTextures.Bound(this.targets.depthNoTranslucents(), this.nearest);
			case "depthtex2" -> new PackTextures.Bound(this.targets.depthNoHand(), this.nearest);
			case "dhDepthTex", "dhDepthTex0" -> new PackTextures.Bound(this.targets.farDepth(), this.nearest);
			case "dhDepthTex1" -> new PackTextures.Bound(this.farUnder(), this.nearest);
			case "shadowtex0" -> this.shadowMap != null ? new PackTextures.Bound(this.shadowMap.depth(), this.nearest) : this.textures.noShadow;
			case "shadowtex1" -> this.shadowMap != null ? new PackTextures.Bound(this.shadowMap.depthNoTranslucents(), this.nearest)
				: this.textures.noShadow;
			case "noisetex" -> this.textures.noise;
			case "normals" -> this.textures.flatNormals;
			case "specular" -> this.textures.black;
			default -> null;
		};
	}

	/** One fullscreen program: into the buffers' other textures, or into {@code screen} for final. */
	private void draw(PackPipelines.Fullscreen f, String stage, @Nullable GpuTextureView screen) {
		List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors = new ArrayList<>();
		for (int b : f.buffers()) colors.add(new RenderPassDescriptor.Attachment<>(screen != null ? screen : this.targets.write(b), Optional.empty()));
		GpuTextureView first = colors.getFirst().textureView();
		RenderPassDescriptor descriptor = new RenderPassDescriptor(() -> "Afterburner " + f.name(), colors, null,
			new RenderPass.RenderArea(0, 0, first.getWidth(0), first.getHeight(0)));
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
			pass.setPipeline(f.pipeline());
			pass.setUniform(UniformLayout.FRAME_BLOCK, this.frameBuffer);
			pass.setUniform(UniformLayout.DRAW_BLOCK, this.drawBuffer);
			this.bindCommon(pass, stage, false);
			for (String sampler : f.samplers()) {
				if (this.textures.custom(stage, sampler) != null) continue;
				if (sampler.startsWith("colortex")) {
					int b = parse(sampler.substring("colortex".length()));
					if (this.targets.has(b)) pass.setUniform(sampler, this.targets.read(b), this.linear);
					else pass.setUniform(sampler, (b == 1 ? this.textures.white : this.textures.black).view(), this.nearest);
				} else if (sampler.equals("Sampler0") || sampler.equals("Sampler2")) {
					pass.setUniform(sampler, this.textures.white.view(), this.textures.white.sampler());
				}
			}
			pass.draw(3, 1, 0, 0);
		}
	}

	private static int parse(String digits) {
		try {
			return Integer.parseInt(digits);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	@Override
	public void close() {
		if (this.upscaler != null) this.upscaler.close();
		if (this.computes != null) this.computes.close();
		this.pipelines.close();
		if (this.storage != null) this.storage.close();
		if (this.shadowMap != null) this.shadowMap.close();
		this.textures.close();
		this.targets.close();
		this.frameBuffer.close();
		this.drawBuffer.close();
		if (this.stageBuffers != null) for (GpuBuffer b : this.stageBuffers) b.close();
		MemoryUtil.memFree(this.frameData);
	}
}
