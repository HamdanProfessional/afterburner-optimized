package com.afterburner.labs.shaderpack.game;

import com.afterburner.Features;
import com.afterburner.client.render.ChunkBatcher;
import com.afterburner.labs.lod.Lod;
import com.afterburner.labs.shaderpack.PackFiles;
import com.afterburner.labs.shaderpack.PackLoader;
import com.afterburner.labs.shaderpack.PackMacros;
import com.afterburner.labs.shaderpack.PackPrograms;
import com.afterburner.labs.shaderpack.PackUniforms;
import com.afterburner.labs.shaderpack.UniformLayout;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One loaded pack drawing the world: the frame's uniforms, the buffers, and the fullscreen passes around the game's own
 * drawing, which goes into the pack's buffers: while a frame is drawn, binding the game's main framebuffer binds ours
 * ({@link #redirect}), and the game's shaders applied there draw with the pack's programs ({@link #applied}).
 * <p>
 * A frame: the pack's images cleared, its setup (first frame only) and begin compute programs, the shadow map (or the one from
 * the frame before, kept while nothing it shows changed, with the images it wrote), shadowcomp, then clear and prepare at the
 * start; the sky and the opaque world; deferred before translucents; translucents; the hand; then composite and final into the
 * game's screen. In each pass group a name's compute programs (composite1_a.csh, ...) run before its fragment program
 * (composite1).
 * <p>
 * With upscaling on, all of it is drawn smaller than the screen, and final draws into a texture that {@link Upscaler} scales up
 * into the screen.
 */
public final class PackRenderer implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger("afterburner");
	/** A slot not resolved yet in the binding table, and one with nothing to bind (an image's sampler, bound by PackStorage). */
	private static final int UNRESOLVED = 0, SKIP = -1;

	/** A pass name's compute programs (if it has any), then its fragment program (if it has one). */
	private record Step(String name, PackPipelines.@Nullable Fullscreen program) {}

	/** What the textures under the pack's sampler names are, for a kind of drawing. */
	private enum Context {
		/** The game's world drawing, into the pack's buffers: those drawn into are read as they were before. */
		WORLD,
		/** The shadow map: no shadow map to read. */
		SHADOW,
		/** A fullscreen pass. */
		FULLSCREEN,
		/** The far terrain, drawing into dhDepthTex (it reads nothing there). */
		FAR,
		/** The far water, drawing into dhDepthTex: reads it without the water. */
		FAR_WATER
	}

	final String name;
	final PackLoader.Loaded pack;
	final PackTargets targets;
	final PackTextures textures;
	final PackPipelines pipelines;
	private final PackSlots slots = new PackSlots();
	/** Null when the pack has no shadow program, or the chunk batcher (which draws it) is off. */
	private final @Nullable ShadowMap shadowMap;
	private final FrameUniforms frame;
	private final int frameBuffer;
	private final int drawBuffer;
	private final ByteBuffer frameData;
	private final PackUniforms.Inputs inputs;
	private final Map<String, List<Step>> passes = new LinkedHashMap<>();
	/** The pack's images and storage buffers, and its compute programs: null without any, or where they can't run. */
	private final @Nullable PackStorage storage;
	private final @Nullable PackComputes computes;
	/** The draw block for each render stage (renderStage set to its number), or null when the pack doesn't read renderStage. */
	private final int @Nullable [] stageBuffers;
	/** What went wrong while drawing (an image or buffer that couldn't be made): logged at the end of the frame's start. */
	private final List<String> lateWarnings = new ArrayList<>();
	private boolean setupRun;
	private final PackPipelines.@Nullable Fullscreen finalPass;
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
	/** The deferred passes ran this frame. */
	private boolean deferredRun;

	/** What the game draws now: the kind of world pass, the part of the frame, and what the sky draws. */
	private PackPass.Kind kind = PackPass.Kind.SKY;
	private Shaderpacks.Phase phase = Shaderpacks.Phase.NONE;
	private Shaderpacks.SkyStage sky = Shaderpacks.SkyStage.NONE;
	/** The world passes' framebuffers, made when first drawn into. */
	private final Map<PackPass.Kind, Gl.Framebuffer> worlds = new EnumMap<>(PackPass.Kind.class);
	/** The framebuffer of fullscreen passes and the far terrain, and how many colors it has attached. */
	private final Gl.Framebuffer passFramebuffer;
	private int passColors;

	/** The texture of each slot ({@link PackSlots}) for the context it was resolved in; reset when that changes. */
	private int[] table = new int[0];
	private @Nullable Context tableContext;
	private PackPass.@Nullable Kind tableKind;
	private String tableStage = "";

	PackRenderer(String name, PackLoader.Loaded pack, PackFiles files, List<String> warnings) {
		this.name = name;
		this.pack = pack;
		RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
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
		this.storage = runs && storage ? new PackStorage(pack, this.slots, warnings) : null;
		this.computes = runs && !pack.computes.isEmpty() ? new PackComputes(pack, this.slots, warnings) : null;
		boolean shadow = pack.get("shadow") != null || pack.get("shadow_solid") != null || pack.get("shadow_cutout") != null;
		if (shadow && !Features.CHUNK_BATCHING.active()) warnings.add("No shadows: they're drawn by batched chunk drawing, which is off");
		this.shadowMap = shadow && Features.CHUNK_BATCHING.active()
			? new ShadowMap(pack, this.frame, this.storage != null ? this.storage.volume() : null)
			: null;
		this.pipelines = new PackPipelines(pack, this.targets, this.shadowMap, this.slots);
		this.inputs = n -> {
			double[] v = pack.customUniforms.value(n);
			if (v != null) return v;
			if (n.equals("alphaTestRef")) return new double[] {0.1};
			return this.frame.get(n);
		};
		int bound = Gl.drawFramebuffer();
		this.passFramebuffer = new Gl.Framebuffer();
		Gl.bindFramebuffer(bound);

		UniformLayout layout = pack.layout;
		this.frameBuffer = Gl.uniformBuffer(layout.frameSize());
		this.drawBuffer = Gl.uniformBuffer(layout.drawSize());
		this.frameData = MemoryUtil.memAlloc(Math.max(16, Math.max(layout.frameSize(), layout.drawSize())));
		Map<String, double[]> drawDefaults = Map.of("entityId", new double[] {0}, "blockEntityId", new double[] {0},
			"currentRenderedItemId", new double[] {0});
		this.upload(this.drawBuffer, true, drawDefaults::get);
		boolean staged = false;
		for (UniformLayout.Member m : layout.members()) staged |= m.perDraw() && m.name().equals("renderStage");
		if (staged) {
			this.stageBuffers = new int[PackMacros.RENDER_STAGES.length];
			for (int i = 0; i < this.stageBuffers.length; i++) {
				double[] value = {i};
				this.stageBuffers[i] = Gl.uniformBuffer(layout.drawSize());
				this.upload(this.stageBuffers[i], true, n -> n.equals("renderStage") ? value : drawDefaults.get(n));
			}
		} else {
			this.stageBuffers = null;
		}

		for (String group : PackPrograms.PASS_GROUPS) {
			List<Step> steps = new ArrayList<>();
			for (int i = 0; i < 100; i++) {
				String passName = i == 0 ? group : group + i;
				// shadowcomp's fragment programs aren't drawn, only its compute ones.
				PackLoader.Program program = group.equals("shadowcomp") ? null : pack.programs.get(passName);
				PackPipelines.Fullscreen f = program == null ? null : this.pipelines.fullscreen(program, false);
				if (f != null || this.computes != null && this.computes.has(passName)) steps.add(new Step(passName, f));
			}
			this.passes.put(group, steps);
		}
		PackLoader.Program finalProgram = pack.get("final");
		this.finalPass = finalProgram == null ? null : this.pipelines.fullscreen(finalProgram, true);
	}

	private void upload(int buffer, boolean perDraw, PackUniforms.Inputs values) {
		int size = perDraw ? this.pack.layout.drawSize() : this.pack.layout.frameSize();
		this.frameData.clear();
		this.pack.layout.write(this.frameData, perDraw, values);
		this.frameData.position(0).limit(size);
		Gl.upload(buffer, this.frameData);
	}

	// ---- The frame ----

	/**
	 * In place of the game's clear before the world: the frame's uniforms, the shadow map, the clears and the prepare passes.
	 * {@code view} and {@code projection} are what the world is drawn with. The sky's buffers are bound after.
	 */
	void beginFrame(Minecraft mc, DeltaTracker delta, Matrix4fc view, Matrix4fc projection) {
		Gl.forgetBindings();
		this.farTerrainDrawn = false;
		this.farFloorDrawn = false;
		this.deferredRun = false;
		this.kind = PackPass.Kind.SKY;
		this.phase = Shaderpacks.Phase.NONE;
		this.sky = Shaderpacks.SkyStage.NONE;
		RenderTarget main = mc.getMainRenderTarget();
		float scale = Shaderpacks.upscale().scale;
		this.scaled = scale < 1.0F && this.upscaler() != null;
		int width = this.scaled ? Math.max(1, Math.round(main.width * scale)) : main.width;
		int height = this.scaled ? Math.max(1, Math.round(main.height * scale)) : main.height;
		this.targets.resize(width, height);
		this.resetTable();
		this.pipelines.beginFrame();
		float partialTick = delta.getGameTimeDeltaPartialTick(false);
		this.frame.update(mc, mc.gameRenderer.getMainCamera(), partialTick, projection, view, width, height);
		this.pack.customUniforms.update(this.frame);
		this.upload(this.frameBuffer, false, this.inputs);
		// From here the game's shaders draw with the pack's programs (the shadow map's draws too).
		this.frameStarted = true;
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
			if (keepShadow) this.shadowMap.keep(mc, this.frame);
			else this.shadowMap.render(mc, this.frame, this.inputs);
			this.shadowMap.renderEntities(mc, this.frame, delta, view);
			if (this.pipelines.shadowWaited()) this.shadowMap.invalidate();
			PackPipelines.restoreUniforms();
			Gl.restoreBlend();
		}
		this.run("shadowcomp", "shadowcomp");
		if (!this.lateWarnings.isEmpty()) {
			for (String w : this.lateWarnings) LOGGER.warn("[Afterburner] {}: {}", this.name, w);
			this.lateWarnings.clear();
		}

		double[] fog = this.frame.get("fogColor");
		this.targets.clear(fog == null ? new Vector4f(0, 0, 0, 1) : new Vector4f((float) fog[0], (float) fog[1], (float) fog[2], 1));
		this.run("prepare", "prepare");
		Gl.baseline();
		this.bindWorld(PackPass.Kind.SKY);
	}

	boolean frameStarted() {
		return this.frameStarted;
	}

	/** The kind of the world's passes after the deferred passes: TRANSLUCENT if the pack's world buffers are split, else WORLD. */
	private PackPass.Kind afterDeferred() {
		return this.pack.splitsWorld() ? PackPass.Kind.TRANSLUCENT : PackPass.Kind.WORLD;
	}

	/** Binds the world pass's framebuffer of {@code kind}: the pack's buffers it draws and the depth. */
	private void bindWorld(PackPass.Kind kind) {
		this.kind = kind;
		Gl.Framebuffer fb = this.worlds.get(kind);
		if (fb == null) {
			fb = new Gl.Framebuffer();
			this.worlds.put(kind, fb);
		}
		fb.bind();
		int[] buffers = PackPipelines.buffers(this.pack, kind);
		for (int i = 0; i < buffers.length; i++) fb.color(i, this.targets.read(buffers[i]));
		fb.depth(this.targets.depth());
		Gl.viewport(this.targets.width, this.targets.height);
	}

	/**
	 * The game binds a framebuffer ({@code target}, {@code id}): its main one, while the world is drawn, is ours instead. Whether
	 * ours was bound (and the game's bind is left out).
	 */
	boolean redirect(int target, int id) {
		if (!this.frameStarted || target != GL30C.GL_FRAMEBUFFER && target != GL30C.GL_DRAW_FRAMEBUFFER) return false;
		if (id != Minecraft.getInstance().getMainRenderTarget().frameBufferId) return false;
		this.bindWorld(this.kind);
		return true;
	}

	/**
	 * The game bound one of its targets: when the frame is drawn smaller, the viewport is the pack's size for the main one (ours),
	 * and the target's own for the others (glowing entities' outlines, drawn at the screen's size).
	 */
	void bound(RenderTarget target) {
		if (!this.frameStarted || !this.scaled) return;
		if (target == Minecraft.getInstance().getMainRenderTarget()) Gl.viewport(this.targets.width, this.targets.height);
		else Gl.viewport(target.viewWidth, target.viewHeight);
	}

	/** The part of the frame the game draws now, from its profiler sections. */
	void phase(Shaderpacks.Phase phase) {
		if (!this.frameStarted) return;
		this.phase = phase;
		switch (phase) {
			case SKY -> this.bindWorld(PackPass.Kind.SKY);
			case TERRAIN -> {
				if (this.kind == PackPass.Kind.SKY) this.bindWorld(PackPass.Kind.WORLD);
			}
			case TRANSLUCENT -> this.beforeTranslucents();
			case HAND -> {
				// depthtex2 is the depth with everything but the hand.
				this.targets.copyDepth(true);
				this.bindWorld(PackPass.Kind.HAND);
			}
			default -> {
			}
		}
	}

	/** What the sky draws now, where its shader doesn't tell. */
	void sky(Shaderpacks.SkyStage stage) {
		this.sky = stage;
	}

	/**
	 * Before translucents: copies the depth to depthtex1 (and depthtex2), runs the deferred passes, draws the far water, and goes
	 * on in the buffers the translucents draw.
	 */
	private void beforeTranslucents() {
		if (this.deferredRun) return;
		this.deferredRun = true;
		PackPipelines.restoreUniforms();
		Gl.restoreBlend();
		this.targets.copyDepth(false);
		this.targets.copyDepth(true);
		this.run("deferred", "deferred");
		this.drawFarWater();
		Gl.baseline();
		this.bindWorld(this.afterDeferred());
	}

	/** Restores what {@link #applied} changed: before the game applies or clears one of its shaders. */
	static void unapply() {
		PackPipelines.restoreUniforms();
		Gl.restoreBlend();
	}

	/**
	 * A game shader was applied (its program in use, its uniforms and textures set): in our world framebuffer or the shadow map,
	 * the pack's program for it is used instead, with the game's uniforms pointed at it, the pack's textures, uniform blocks and
	 * blending.
	 */
	void applied(ShaderInstance shader) {
		if (!this.frameStarted) return;
		int framebuffer = Gl.drawFramebuffer();
		PackPass.Kind kind = this.shadowMap != null ? this.shadowMap.drawing(framebuffer) : null;
		Context context;
		Gl.Framebuffer target = null;
		if (kind != null) {
			context = Context.SHADOW;
		} else {
			target = this.worlds.get(this.kind);
			if (target == null || target.id != framebuffer) return;
			kind = this.kind;
			context = Context.WORLD;
		}
		PackPipelines.Use use = this.pipelines.use(shader, kind, this.phase, Math.max(0, ChunkBatcher.drawingFormat()), Gl.blending(),
			this.sky);
		PackPipelines.World w = use.world;
		if (w == null) {
			// The game's own program, into colortex0.
			if (target != null) target.drawBuffers(this.vanillaBuffers(kind));
			return;
		}
		int[] locations = use.locations;
		if (w == PackPipelines.NONE || w.program == null || locations == null) {
			Gl.useProgram(this.pipelines.nothing().id);
			PackPipelines.swapUniforms(shader, PackPipelines.nowhere(shader));
			return;
		}
		Gl.useProgram(w.program.id);
		PackPipelines.swapUniforms(shader, locations);
		this.bindSlots(w.slots, context, kind, context == Context.SHADOW ? "shadow" : "gbuffers");
		this.bindBlocks(w.stage);
		if (target != null) target.drawBuffers(w.drawBuffers);
		else this.shadowMap.drawBuffers(kind, w.drawBuffers);
		Gl.overrideBlend(w.blend, w.perAttachment);
		// The shadow programs draw both sides (the sun sees the back of what faces the camera).
		if (kind.shadow()) GlStateManager._disableCull();
	}

	/** Where the game's own programs draw in a world pass: colortex0, as its output 0. */
	private int[] vanillaBuffers(PackPass.Kind kind) {
		int[] buffers = PackPipelines.buffers(this.pack, kind);
		for (int i = 0; i < buffers.length; i++) if (buffers[i] == 0) return new int[] {i};
		return new int[] {-1};
	}

	private void bindBlocks(int stage) {
		Gl.bindUniformBuffer(Gl.FRAME_BINDING, this.frameBuffer);
		int[] stages = this.stageBuffers;
		Gl.bindUniformBuffer(Gl.DRAW_BINDING, stages != null && stage >= 0 && stage < stages.length ? stages[stage] : this.drawBuffer);
	}

	// ---- The pack's textures ----

	private void resetTable() {
		Arrays.fill(this.table, UNRESOLVED);
	}

	/** Binds the textures of {@code slots} as the pack names them in {@code context}. */
	private void bindSlots(int[] slots, Context context, PackPass.@Nullable Kind kind, String stage) {
		if (context != this.tableContext || kind != this.tableKind || !stage.equals(this.tableStage)) {
			this.tableContext = context;
			this.tableKind = kind;
			this.tableStage = stage;
			this.resetTable();
		}
		if (this.table.length < this.slots.size()) this.table = Arrays.copyOf(this.table, Math.max(this.slots.size(), this.table.length * 2 + 8));
		for (int slot : slots) {
			int texture = this.table[slot];
			if (texture == UNRESOLVED) {
				String name = this.slots.name(slot);
				ExternalBindings.Slot external = ExternalBindings.get(name);
				texture = external != null && external.kind() == ExternalBindings.Kind.SAMPLER ? SKIP : this.resolve(name, context, kind, stage);
				this.table[slot] = texture;
			}
			if (texture > 0) Gl.bindUnit(PackSlots.unit(slot), GL11C.GL_TEXTURE_2D, texture);
		}
		Gl.restoreActiveTexture();
	}

	/** What a sampler name reads in {@code context}. */
	private int resolve(String sampler, Context context, PackPass.@Nullable Kind kind, String stage) {
		int custom = this.textures.custom(stage, sampler);
		if (custom != 0) return custom;
		boolean world = context == Context.WORLD || context == Context.FAR || context == Context.FAR_WATER;
		if (sampler.startsWith("colortex")) {
			int b = parse(sampler.substring("colortex".length()));
			if (!this.targets.has(b)) return b == 1 ? this.textures.white : this.textures.black;
			if (world && kind != null) {
				// The world draws into a buffer's latest texture, so reading it gets the other one.
				for (int drawn : PackPipelines.buffers(this.pack, kind)) if (drawn == b) return this.targets.write(b);
			}
			return this.targets.read(b);
		}
		ShadowMap map = context == Context.SHADOW ? null : this.shadowMap;
		if (sampler.startsWith("shadowcolor")) {
			int color = map != null ? map.color(parse(sampler.substring("shadowcolor".length()))) : 0;
			return color != 0 ? color : this.textures.white;
		}
		return switch (sampler) {
			// The world draws into depthtex0, so it reads the copy.
			case "depthtex0" -> world ? this.targets.depthNoTranslucents() : this.targets.depth();
			case "depthtex1" -> this.targets.depthNoTranslucents();
			case "depthtex2" -> this.targets.depthNoHand();
			case "dhDepthTex", "dhDepthTex0" -> context == Context.FAR ? this.textures.noShadow
				: context == Context.FAR_WATER ? this.targets.farDepthSolid() : this.targets.farDepth();
			case "dhDepthTex1" -> context == Context.FAR ? this.textures.noShadow : this.farUnder();
			case "shadowtex0" -> map != null ? map.depth() : this.textures.noShadow;
			case "shadowtex1" -> map != null ? map.depthNoTranslucents() : this.textures.noShadow;
			case "noisetex" -> this.textures.noise;
			// The normal and specular maps: flat and blank, as no resource pack gives them yet.
			case "normals" -> this.textures.flatNormals;
			case "specular" -> this.textures.black;
			case "Sampler0", "Sampler1", "Sampler2" -> this.textures.white;
			default -> this.textures.black;
		};
	}

	/** dhDepthTex1: the far terrain without its water drawn on its own, and from that on with the floor under it. */
	private int farUnder() {
		return this.farFloorDrawn ? this.targets.farDepthFloor() : this.targets.farDepthSolid();
	}

	private static int parse(String digits) {
		try {
			return Integer.parseInt(digits);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	// ---- The far terrain ----

	/** Whether the pack draws Afterburner's far terrain: it has Distant Horizons' terrain program. */
	boolean drawsFarTerrain() {
		return !this.farTerrainFailed && this.pack.get("dh_terrain") != null;
	}

	/** Whether the pack draws the far terrain's water on its own, see-through: it has Distant Horizons' water program. */
	boolean drawsFarWater() {
		return !this.farWaterFailed && this.pack.get("dh_water") != null;
	}

	Matrix4fc farTerrainProjection() {
		return this.frame.dhProjection;
	}

	/** Binds the pass framebuffer with these colors and depth (0 for none). */
	private Gl.Framebuffer pass(int[] colors, int depth) {
		Gl.Framebuffer fb = this.passFramebuffer.bind();
		for (int i = 0; i < colors.length; i++) fb.color(i, colors[i]);
		for (int i = colors.length; i < this.passColors; i++) fb.color(i, 0);
		this.passColors = colors.length;
		fb.depth(depth);
		return fb;
	}

	/** The world's buffers of {@code kind} as they're drawn into (their latest textures). */
	private int[] worldColors(PackPass.Kind kind) {
		int[] buffers = PackPipelines.buffers(this.pack, kind);
		int[] colors = new int[buffers.length];
		for (int i = 0; i < buffers.length; i++) colors[i] = this.targets.read(buffers[i]);
		return colors;
	}

	/**
	 * Afterburner's far terrain, with the pack's dh_terrain (as Distant Horizons' is with Iris): before the game's terrain, into
	 * the world's buffers and a depth of its own (dhDepthTex), so the game's terrain is drawn over it, and the pack's later
	 * passes find it where the game drew nothing. Once the far terrain is prepared for the frame.
	 */
	void drawFarTerrain() {
		if (!this.frameStarted || this.farTerrainDrawn) return;
		this.farTerrainDrawn = true;
		if (this.farTerrainFailed || !this.drawsFarTerrain() || !Lod.drawsForPack()) return;
		PackPipelines.World w = this.pipelines.farTerrain();
		if (w == null || w.program == null) return;
		PackPipelines.restoreUniforms();
		Gl.restoreBlend();
		// The water on its own later (drawFarWater), once what it's drawn with is ready; till then with the rest.
		boolean water = this.drawsFarWater() && Lod.hasWaterForPack() && this.farWaterReady();
		try {
			this.pass(this.worldColors(PackPass.Kind.WORLD), this.targets.farDepth()).drawBuffers(w.drawBuffers);
			Gl.viewport(this.targets.width, this.targets.height);
			Gl.baseline();
			Gl.useProgram(w.program.id);
			this.bindBlocks(w.stage);
			this.bindSlots(w.slots, Context.FAR, PackPass.Kind.WORLD, "gbuffers");
			Gl.overrideBlend(w.blend, w.perAttachment);
			Lod.drawForPack(w.program.id, water);
		} catch (RuntimeException e) {
			// The far terrain goes, not the pack.
			this.farTerrainFailed = true;
			LOGGER.error("[Afterburner] {}: the far terrain failed, it's off with this pack", this.name, e);
		} finally {
			Gl.restoreBlend();
		}
		// dhDepthTex1: without the water drawn later.
		this.targets.copyFarDepth(false);
		this.bindWorld(this.kind);
	}

	/** Whether what the far water is drawn with is ready: the pack's dh_water for it, and the floor under it. */
	private boolean farWaterReady() {
		if (this.pipelines.farWater(this.afterDeferred()) == null) return false;
		if (Lod.floorProgram() != 0) return true;
		this.farWaterFailed = true;
		LOGGER.error("[Afterburner] {}: the far water's floor shader didn't build; far water is drawn solid with this pack", this.name);
		return false;
	}

	/**
	 * The far terrain's water, with the pack's dh_water, after the deferred passes as the game's translucents are (Iris draws
	 * Distant Horizons' water so). The far terrain keeps no ground under its water, and packs see through water to what's
	 * under it (dhDepthTex1), fading it out over nothing: first a floor's depth under it there (far_water_floor), then the
	 * water into the world's buffers and dhDepthTex, blended.
	 */
	private void drawFarWater() {
		if (this.farWaterFailed || !Lod.waterPendingForPack()) return;
		int floor = Lod.floorProgram();
		PackPass.Kind kind = this.afterDeferred();
		PackPipelines.World w = this.pipelines.farWater(kind);
		if (floor == 0 || w == null || w.program == null) return;
		try {
			this.targets.copyFarDepth(true);
			this.pass(new int[0], this.targets.farDepthFloor()).drawBuffers(new int[0]);
			Gl.viewport(this.targets.width, this.targets.height);
			Gl.baseline();
			GlStateManager._colorMask(false, false, false, false);
			Gl.useProgram(floor);
			Lod.drawWaterForPack(floor, false);
			GlStateManager._colorMask(true, true, true, true);
			this.farFloorDrawn = true;
			this.resetTable();

			this.pass(this.worldColors(kind), this.targets.farDepth()).drawBuffers(w.drawBuffers);
			GlStateManager._enableBlend();
			GlStateManager._blendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);
			Gl.useProgram(w.program.id);
			this.bindBlocks(w.stage);
			this.bindSlots(w.slots, Context.FAR_WATER, kind, "gbuffers");
			Gl.overrideBlend(w.blend, w.perAttachment);
			Lod.drawWaterForPack(w.program.id, true);
		} catch (RuntimeException e) {
			// From the next frame it's drawn with the rest.
			this.farWaterFailed = true;
			LOGGER.error("[Afterburner] {}: the far water failed; it's drawn solid with this pack", this.name, e);
		} finally {
			Gl.restoreBlend();
			GlStateManager._colorMask(true, true, true, true);
			GlStateManager._disableBlend();
			GlStateManager._blendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE, GL11C.GL_ZERO);
		}
	}

	// ---- The end of the frame ----

	/** After the hand: composite, then final into the game's screen. */
	void finish(Minecraft mc) {
		if (!this.frameStarted) return;
		PackPipelines.restoreUniforms();
		Gl.restoreBlend();
		if (!this.deferredRun) {
			// Not drawn this frame (a screen over the world, ...): as it would be.
			this.deferredRun = true;
			this.targets.copyDepth(false);
			this.targets.copyDepth(true);
			this.run("deferred", "deferred");
		}
		this.run("composite", "composite");
		this.frameStarted = false;
		RenderTarget main = mc.getMainRenderTarget();
		Upscaler upscaler = this.scaled ? this.upscaler : null;
		if (upscaler != null) {
			// No final: colortex0 is the picture to scale up.
			int picture = this.targets.read(0);
			if (this.finalPass != null) {
				int input = upscaler.inputFramebuffer(this.targets.width, this.targets.height);
				this.draw(this.finalPass, "composite", input, this.targets.width, this.targets.height);
				picture = upscaler.inputTexture();
			}
			Gl.fullscreenState();
			upscaler.run(picture, this.targets.width, this.targets.height, main.frameBufferId, main.width, main.height);
		} else if (this.finalPass != null) {
			this.draw(this.finalPass, "composite", main.frameBufferId, main.width, main.height);
		} else {
			// No final: colortex0 is the picture.
			this.pass(new int[] {this.targets.read(0)}, 0);
			Gl.blit(this.passFramebuffer.id, this.targets.width, this.targets.height, main.frameBufferId, main.width, main.height);
		}
		Gl.baseline();
		Gl.bindFramebuffer(main.frameBufferId);
		Gl.viewport(main.viewWidth, main.viewHeight);
	}

	/** The upscaler, made on first use; null if its programs didn't compile (then the world is drawn full size). */
	private @Nullable Upscaler upscaler() {
		if (this.upscaler == null && !this.upscalerFailed) {
			Upscaler u = new Upscaler();
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
			this.draw(step.program, stage, -1, 0, 0);
			for (int b : step.program.buffers()) this.targets.flip(b);
			this.resetTable();
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
		c.run(passName, sampler -> {
			ExternalBindings.Slot external = ExternalBindings.get(sampler);
			return external != null && external.kind() == ExternalBindings.Kind.SAMPLER ? 0 : this.resolve(sampler, Context.FULLSCREEN, null, stage);
		}, this.frameBuffer, this.drawBuffer, this.targets.width, this.targets.height);
		this.resetTable();
	}

	/**
	 * One fullscreen program: into the buffers' other textures, or into {@code screen} (a framebuffer {@code width} by
	 * {@code height}) for final.
	 */
	private void draw(PackPipelines.Fullscreen f, String stage, int screen, int width, int height) {
		if (screen >= 0) {
			Gl.bindFramebuffer(screen);
		} else {
			int[] colors = new int[f.buffers().length];
			for (int i = 0; i < colors.length; i++) colors[i] = this.targets.write(f.buffers()[i]);
			this.pass(colors, 0).drawBuffers(Gl.all(colors.length));
			width = this.targets.width;
			height = this.targets.height;
		}
		Gl.viewport(width, height);
		Gl.fullscreenState();
		Gl.useProgram(f.program().id);
		this.bindBlocks(-1);
		this.bindSlots(f.slots(), Context.FULLSCREEN, null, stage);
		Gl.overrideBlend(f.blend(), f.perBuffer());
		Gl.drawFullscreen();
		Gl.restoreBlend();
	}

	@Override
	public void close() {
		PackPipelines.restoreUniforms();
		Gl.restoreBlend();
		if (this.upscaler != null) this.upscaler.close();
		if (this.computes != null) this.computes.close();
		this.pipelines.close();
		if (this.storage != null) this.storage.close();
		if (this.shadowMap != null) this.shadowMap.close();
		this.textures.close();
		this.targets.close();
		for (Gl.Framebuffer fb : this.worlds.values()) fb.close();
		this.worlds.clear();
		this.passFramebuffer.close();
		Gl.deleteBuffer(this.frameBuffer);
		Gl.deleteBuffer(this.drawBuffer);
		if (this.stageBuffers != null) for (int b : this.stageBuffers) Gl.deleteBuffer(b);
		MemoryUtil.memFree(this.frameData);
		Gl.useProgram(0);
	}
}
