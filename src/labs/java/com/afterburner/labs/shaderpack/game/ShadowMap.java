package com.afterburner.labs.shaderpack.game;

import com.afterburner.client.render.ChunkBatcher;
import com.afterburner.labs.shaderpack.PackUniforms;
import com.afterburner.labs.shaderpack.PackLoader;
import com.afterburner.labs.shaderpack.TranslateTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * The shadow map: the terrain as the sun (or moon) sees it, drawn at the start of a frame with the pack's shadow programs, or kept
 * from the frame before while nothing it shows changed ({@link #holds}, {@link Shaderpacks.ShadowUpdates}).
 * Its depth is shadowtex0, its colors shadowcolor0-1; shadowtex1 is its depth without water and glass (the terrain's is copied
 * before they're drawn, as with Iris), or shadowtex0 when the pack's shadowTranslucent is false and they cast no shadow.
 * <p>
 * The chunk batcher draws the solid and cutout sections inside the shadow's box (shadowDistance either side, 256 blocks
 * deep), and those in the pack's voxel volume around the camera (its 3D images, which packs fill from the shadow pass: colored
 * lighting) wherever the sun is. Entities are drawn in every frame, over the terrain, into both depths ({@link #renderEntities}).
 */
final class ShadowMap implements AutoCloseable {
	static final int COLORS = 2;
	private static final int USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST
		| GpuTexture.USAGE_COPY_SRC;
	/** How far the camera may move, in blocks, before the sections are picked again; they're picked from a box that much bigger. */
	private static final double MOVE = 4.0;
	/** How much the sun may turn (in the view's rotation, about radians) before the sections are picked again. */
	private static final float TURN = 0.005F;
	/** Half a section's diagonal: a section whose middle is farther than this outside the box has nothing in it. */
	private static final double SECTION_RADIUS = 8.0 * Math.sqrt(3.0);
	/** The far end of the shadow's view, as in its projection. */
	private static final float DEPTH = 256.0F;
	/** Uniforms that change every frame or tick: shadow programs reading them animate the shadows (waving leaves and grass). */
	private static final Set<String> CLOCKS = Set.of("frameTimeCounter", "frameTime", "frameCounter", "worldTime");
	/**
	 * Uniforms a kept map already follows: the camera (moving it within the pack's shadowIntervalSize doesn't move the map), the
	 * view (shadows don't depend on where the camera looks) and the sun (the map is drawn again at each step it turns).
	 */
	private static final Set<String> FOLLOWED = Set.of("cameraPosition", "cameraPositionInt", "cameraPositionFract", "previousCameraPosition",
		"previousCameraPositionInt", "previousCameraPositionFract", "eyePosition", "relativeEyePosition", "eyeAltitude", "playerLookVector",
		"gbufferModelView", "gbufferModelViewInverse", "gbufferProjection", "gbufferProjectionInverse", "gbufferPreviousModelView",
		"gbufferPreviousProjection", "shadowModelView", "shadowModelViewInverse", "shadowProjection", "shadowProjectionInverse",
		"sunPosition", "moonPosition", "shadowLightPosition", "upPosition", "endFlashPosition", "sunAngle", "shadowAngle", "worldDay",
		"skyColor", "fogColor");
	final int resolution;
	private final float distance;
	private final float interval;
	/** How far from the camera entities cast shadows (the pack's shadowDistance times its entityShadowDistanceMul). */
	private final double entityRange;
	/** The pack's voxel volume (its biggest 3D image), in blocks around the camera, or null without one. */
	private final int @Nullable [] volume;
	/** The shadowcolor buffers the shadow programs draw, as the pass's color attachments. */
	private final int[] buffers;
	private final GpuFormat[] formats = new GpuFormat[COLORS];
	private final boolean[] clear = new boolean[COLORS];
	private final Vector4f[] clearColors = new Vector4f[COLORS];
	private final List<GpuTexture> textures = new ArrayList<>();
	private final List<GpuTextureView> views = new ArrayList<>();
	private final GpuTexture depth;
	private final GpuTextureView depthView;
	/** shadowtex1, the depth before water and glass are drawn, or null when they aren't drawn (shadowTranslucent=false). */
	private final @Nullable GpuTexture depthSolid;
	private final @Nullable GpuTextureView depthSolidView;
	private final @Nullable GpuTextureView[] colorViews = new GpuTextureView[COLORS];
	/** The shadow's projection as the game's Projection block. */
	private final GpuBuffer projection;
	private final ByteBuffer projectionData = MemoryUtil.memAlloc(64);

	/** The sections picked last, and what for. */
	private final ObjectArrayList<SectionRenderDispatcher.RenderSection> sections = new ObjectArrayList<>();
	private final Matrix4f pickedView = new Matrix4f();
	private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
	private @Nullable ViewArea pickedArea;
	private boolean picked;
	private long pickedSection;
	private double pickedX, pickedY, pickedZ;

	/** This frame's sections are picked and their draws prepared ({@link #holds}), and whether there's anything to draw. */
	private boolean prepared, any;
	private long contents;
	/** The pack's images the shadow programs write: kept with the map. */
	private final Set<String> written = new HashSet<>();
	/** Whether the shadow programs read a clock ({@link #CLOCKS}), and the other values they read, which keep the map while they hold. */
	private final boolean animated;
	private final List<String> watched = new ArrayList<>();
	private String @Nullable [] compared;
	private double @Nullable [] @Nullable [] drawnValues;
	/** Whether the map holds what it was last drawn for (below). */
	private boolean valid;
	private final Matrix3f drawnRotation = new Matrix3f();
	private final Matrix3f rotation = new Matrix3f();
	private double drawnX, drawnY, drawnZ;
	private long drawnContents;
	/** How the map was to be kept when it was drawn: drawn again in full when that changes. */
	private Shaderpacks.ShadowUpdates drawnMode = Shaderpacks.ShadowUpdates.SMART;
	/** Whether the terrain is drawn again next frame, in full or but for its solid blocks ({@link #refreshes}). */
	private boolean drawnEachFrame;
	/**
	 * The map with the terrain alone, which a kept map goes back to before the frame's entities are drawn in: its depth and the
	 * shadowcolor buffers drawn, each beside its copy.
	 */
	private final List<GpuTexture[]> terrainCopies = new ArrayList<>();
	/** The map with the solid blocks alone, which a map {@link #refreshes refreshed} goes back to: the same, each beside its copy. */
	private final List<GpuTexture[]> solidCopies = new ArrayList<>();
	/** Whether {@link #terrainDepth} holds the terrain as last drawn, and whether entities are drawn in the map. */
	private boolean terrainSaved, entitiesDrawn;
	/** Whether the terrain drawn last had water or glass in it. */
	private boolean translucentsDrawn;

	ShadowMap(PackLoader.Loaded pack, FrameUniforms frame, int @Nullable [] volume) {
		this.volume = volume;
		this.resolution = Mth.clamp((int) frame.number("shadowMapResolution", 1024), 16, 8192);
		this.distance = (float) frame.number("shadowDistance", 160.0);
		this.interval = (float) frame.number("shadowIntervalSize", 2.0);
		this.entityRange = this.distance * frame.number("entityShadowDistanceMul", 1.0);
		TreeSet<Integer> drawn = new TreeSet<>();
		Set<String> used = new HashSet<>();
		for (PackLoader.Program p : pack.programs.values()) {
			if (p.kind() != TranslateTarget.Kind.SHADOW) continue;
			for (int b : p.drawBuffers()) if (b >= 0 && b < COLORS) drawn.add(b);
			used.addAll(p.vs().names());
			used.addAll(p.fs().names());
		}
		for (String image : pack.images.images.keySet()) if (used.contains(image)) this.written.add(image);
		// The pack's own uniforms (and variables) are clocks or followed when what they're made from is.
		Set<String> clocks = new HashSet<>(CLOCKS), followed = new HashSet<>(FOLLOWED);
		for (PackUniforms.Definition d : pack.customUniforms.definitions()) {
			Set<String> refs = Set.copyOf(List.of(d.source().split("\\W+")));
			if (refs.stream().anyMatch(clocks::contains)) clocks.add(d.name());
			else if (refs.stream().anyMatch(followed::contains)) followed.add(d.name());
		}
		boolean animated = false;
		for (String name : used) {
			if (clocks.contains(name)) animated = true;
			else if (!followed.contains(name) && !this.written.contains(name)) this.watched.add(name);
		}
		this.animated = animated;
		// A pass needs somewhere to draw even if the programs only write depth.
		if (drawn.isEmpty()) drawn.add(0);
		this.buffers = drawn.stream().mapToInt(Integer::intValue).toArray();

		GpuDevice device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();
		this.depth = device.createTexture("Afterburner shadowtex", USAGE, GpuFormat.D32_FLOAT, this.resolution, this.resolution, 1, 1);
		this.depthView = this.keep(this.depth);
		encoder.clearDepthTexture(this.depth, 1.0);
		if (pack.properties.getBoolean("shadowTranslucent", true)) {
			this.depthSolid = device.createTexture("Afterburner shadowtex1", USAGE, GpuFormat.D32_FLOAT, this.resolution, this.resolution, 1, 1);
			this.depthSolidView = this.keep(this.depthSolid);
			encoder.clearDepthTexture(this.depthSolid, 1.0);
		} else {
			this.depthSolid = null;
			this.depthSolidView = null;
		}
		for (int b : this.buffers) {
			String format = setting(pack.consts, b, "Format");
			this.formats[b] = format == null ? GpuFormat.RGBA8_UNORM : PackTargets.format(format);
			this.clear[b] = !"false".equals(setting(pack.consts, b, "Clear"));
			String color = setting(pack.consts, b, "ClearColor");
			this.clearColors[b] = color != null ? PackTargets.vec4(color) : new Vector4f(1, 1, 1, 1);
			GpuTexture t = device.createTexture("Afterburner shadowcolor" + b, USAGE, this.formats[b], this.resolution, this.resolution, 1, 1);
			this.colorViews[b] = this.keep(t);
			// Those not cleared every frame start out cleared.
			encoder.clearColorTexture(t, this.clearColors[b]);
		}
		this.projection = device.createBuffer(() -> "Afterburner shadow projection", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 64);
	}

	/** shadowcolor0's settings may also be named shadowcolor's. */
	private static @Nullable String setting(Map<String, String> consts, int buffer, String what) {
		String value = consts.get("shadowcolor" + buffer + what);
		if (value == null && buffer == 0) value = consts.get("shadowcolor" + what);
		return value;
	}

	private GpuTextureView keep(GpuTexture t) {
		GpuTextureView view = RenderSystem.getDevice().createTextureView(t);
		this.textures.add(t);
		this.views.add(view);
		return view;
	}

	int[] buffers() {
		return this.buffers;
	}

	GpuFormat format(int buffer) {
		return this.formats[buffer];
	}

	GpuTextureView depth() {
		return this.depthView;
	}

	/** shadowtex1: the depth without water and glass. */
	GpuTextureView depthNoTranslucents() {
		return this.depthSolidView != null ? this.depthSolidView : this.depthView;
	}

	/** A shadowcolor buffer, or null if the shadow programs don't draw it. */
	@Nullable GpuTextureView color(int buffer) {
		return buffer >= 0 && buffer < COLORS ? this.colorViews[buffer] : null;
	}

	/** The pack's images the shadow programs write, which a kept map keeps (they aren't cleared). */
	Set<String> written() {
		return this.written;
	}

	/**
	 * Whether the map drawn before still shows this frame's shadows, so it needn't be drawn again: the same terrain in it, the
	 * sun at the same step, the camera in the same shadowIntervalSize cell (the same block, if the programs write images, which
	 * packs fill around the camera's block) and the same values of what else the programs read. Picks and prepares this frame's
	 * draws either way. Asked before the pack's images are cleared.
	 */
	boolean holds(Minecraft mc, FrameUniforms frame, PackUniforms.Inputs inputs) {
		this.prepared = false;
		ViewArea area = mc.levelRenderer.viewArea();
		SectionRenderDispatcher dispatcher = mc.levelRenderer.sectionRenderDispatcher();
		if (!frame.hasShadow || area == null || dispatcher == null) return false;
		Vec3 camera = mc.gameRenderer.mainCamera().position();
		this.prepare(area, dispatcher, camera, frame);
		Shaderpacks.ShadowUpdates mode = Shaderpacks.shadowUpdates();
		if (!this.valid || mode == Shaderpacks.ShadowUpdates.EVERY_FRAME || mode != this.drawnMode || this.contents != this.drawnContents) {
			return false;
		}
		if (!frame.shadowView.get3x3(this.rotation).equals(this.drawnRotation)) return false;
		if (!this.sameCell(camera.x, this.drawnX) || !this.sameCell(camera.y, this.drawnY) || !this.sameCell(camera.z, this.drawnZ)) return false;
		if (!this.written.isEmpty() && (Math.floor(camera.x) != Math.floor(this.drawnX) || Math.floor(camera.y) != Math.floor(this.drawnY)
				|| Math.floor(camera.z) != Math.floor(this.drawnZ))) {
			return false;
		}
		String[] names = this.compared;
		double[][] values = this.drawnValues;
		if (names == null || values == null) return false;
		for (int i = 0; i < names.length; i++) {
			if (!near(inputs.get(names[i]), values[i])) return false;
		}
		return true;
	}

	/**
	 * Whether a value is still about what it was when the map was drawn. Smoothed ones (wetness, ...) creep toward where they're
	 * going for minutes: the map is drawn again each time one has moved on by a thousandth, not every frame.
	 */
	private static boolean near(double @Nullable [] now, double @Nullable [] then) {
		if (now == null || then == null || now.length != then.length) return now == then;
		for (int i = 0; i < now.length; i++) {
			if (!(Math.abs(now[i] - then[i]) <= 1e-3 * Math.max(1.0, Math.abs(then[i])))) return false;
		}
		return true;
	}

	/**
	 * Whether two camera coordinates are in the same shadowIntervalSize cell, where the shadow's view (moved by the camera's
	 * remainder, as {@link FrameUniforms} moves it) shows the world the same; without cells, only the same place does.
	 */
	private boolean sameCell(double a, double b) {
		if (this.interval <= 0) return a == b;
		return (long) (a / this.interval) == (long) (b / this.interval);
	}

	/**
	 * With {@link Shaderpacks.ShadowUpdates#SMART}, whether the programs wave leaves and grass (they read a clock): a map kept is
	 * then kept for its solid blocks alone, the rest drawn again over them every frame, so the waving is as smooth as the frames.
	 */
	private boolean refreshes(Shaderpacks.ShadowUpdates mode) {
		return mode == Shaderpacks.ShadowUpdates.SMART && this.animated;
	}

	/** The map drawn before is used again this frame ({@link #holds}): as it is, or drawn again but for the solid blocks. */
	void keep(Minecraft mc, FrameUniforms frame, Consumer<RenderPass> bind) {
		this.prepared = false;
		if (this.refreshes(this.drawnMode) && !this.solidCopies.isEmpty()) this.draw(mc, frame, bind, false);
	}

	/** Something wasn't drawn into the map (a shadow program still compiling): draw it again next frame. */
	void invalidate() {
		this.valid = false;
	}

	private void prepare(ViewArea area, SectionRenderDispatcher dispatcher, Vec3 camera, FrameUniforms frame) {
		this.pick(area, camera, frame.shadowView);
		ChunkBatcher.ShadowFrame draws = ChunkBatcher.shadowFrame();
		this.any = draws.prepare(this.sections, dispatcher);
		this.contents = draws.contents();
		this.prepared = true;
	}

	/** What a kept map is checked against ({@link #holds}). */
	private void remember(Vec3 camera, FrameUniforms frame, PackUniforms.Inputs inputs) {
		this.valid = true;
		this.drawnMode = Shaderpacks.shadowUpdates();
		this.drawnContents = this.contents;
		frame.shadowView.get3x3(this.drawnRotation);
		this.drawnX = camera.x;
		this.drawnY = camera.y;
		this.drawnZ = camera.z;
		// The names the programs use that are values (not functions or their own variables), found once they all have one.
		if (this.compared == null) {
			this.compared = this.watched.stream().filter(n -> inputs.get(n) != null).toArray(String[]::new);
			this.drawnValues = new double[this.compared.length][];
		}
		for (int i = 0; i < this.compared.length; i++) {
			double[] v = inputs.get(this.compared[i]);
			this.drawnValues[i] = v == null ? null : v.clone();
		}
	}

	/**
	 * Draws this frame's shadow map, before anything else is drawn: the terrain inside the shadow's box. {@code bind} binds the
	 * pack's uniforms and textures in the pass.
	 */
	void render(Minecraft mc, FrameUniforms frame, PackUniforms.Inputs inputs, Consumer<RenderPass> bind) {
		ViewArea area = mc.levelRenderer.viewArea();
		SectionRenderDispatcher dispatcher = mc.levelRenderer.sectionRenderDispatcher();
		if (!frame.hasShadow || area == null || dispatcher == null) {
			this.valid = false;
			return;
		}
		Vec3 camera = mc.gameRenderer.mainCamera().position();
		if (!this.prepared) this.prepare(area, dispatcher, camera, frame);
		this.prepared = false;
		this.remember(camera, frame, inputs);
		this.draw(mc, frame, bind, true);
	}

	/**
	 * Draws the terrain into the map: in {@code full}, or over the solid blocks kept from when it was (the map
	 * {@link #refreshes}), which then get their copy.
	 */
	private void draw(Minecraft mc, FrameUniforms frame, Consumer<RenderPass> bind, boolean full) {
		this.terrainSaved = false;
		this.entitiesDrawn = false;
		boolean split = this.refreshes(this.drawnMode);
		this.drawnEachFrame = split || this.drawnMode == Shaderpacks.ShadowUpdates.EVERY_FRAME;
		ChunkBatcher.ShadowFrame draws = ChunkBatcher.shadowFrame();
		boolean any = this.any;
		draws.upload();

		// Everything the pass reads is written before it's opened.
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		frame.shadowProjection.get(0, this.projectionData);
		encoder.writeToBuffer(this.projection.slice(), this.projectionData);
		GpuTextureView atlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
		GpuBufferSlice terrain = RenderSystem.getDynamicUniforms().writeTerrainTransform(frame.shadowView, atlas.getWidth(0), atlas.getHeight(0));
		RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);

		RenderPass.RenderArea bounds = new RenderPass.RenderArea(0, 0, this.resolution, this.resolution);
		GpuBuffer indexBuffer = indices.getBuffer();
		boolean drawing = any && indexBuffer != null;
		if (any && indexBuffer == null) this.valid = false;
		List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> kept = new ArrayList<>();
		for (int b : this.buffers) kept.add(new RenderPassDescriptor.Attachment<>(this.colorViews[b], Optional.empty()));
		if (full) {
			List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors = new ArrayList<>();
			for (int b : this.buffers) {
				colors.add(new RenderPassDescriptor.Attachment<>(this.colorViews[b], this.clear[b] ? Optional.of(this.clearColors[b]) : Optional.empty()));
			}
			RenderPassDescriptor descriptor = new RenderPassDescriptor(() -> "Afterburner shadow map", colors,
				new RenderPassDescriptor.Attachment<>(this.depthView, OptionalDouble.of(1.0)), bounds);
			try (RenderPass pass = encoder.createRenderPass(descriptor)) {
				if (drawing) {
					this.begin(pass, mc, terrain, atlas, bind);
					for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
						if (!layer.translucent() && (!split || layer == ChunkSectionLayer.SOLID)) draws.draw(layer, pass, indexBuffer, indices.type());
					}
				}
			}
			if (split) {
				if (this.solidCopies.isEmpty()) {
					this.solidCopies.add(this.copyOf(this.depth));
					for (int b : this.buffers) this.solidCopies.add(this.copyOf(this.colorViews[b].texture()));
				}
				for (GpuTexture[] c : this.solidCopies) encoder.copyTextureToTexture(c[0], c[1], 0, 0, 0, 0, 0, this.resolution, this.resolution);
			}
		} else {
			for (GpuTexture[] c : this.solidCopies) encoder.copyTextureToTexture(c[1], c[0], 0, 0, 0, 0, 0, this.resolution, this.resolution);
		}
		// Over the solid blocks kept, the rest of what isn't see-through (leaves, grass): this frame's waving.
		if (split && drawing) {
			RenderPassDescriptor cutouts = new RenderPassDescriptor(() -> "Afterburner shadow map cutouts", kept,
				new RenderPassDescriptor.Attachment<>(this.depthView, OptionalDouble.empty()), bounds);
			try (RenderPass pass = encoder.createRenderPass(cutouts)) {
				this.begin(pass, mc, terrain, atlas, bind);
				for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
					if (!layer.translucent() && layer != ChunkSectionLayer.SOLID) draws.draw(layer, pass, indexBuffer, indices.type());
				}
			}
		}
		// Water and glass go in after shadowtex1 got the map without them.
		this.translucentsDrawn = false;
		if (this.depthSolid == null) return;
		encoder.copyTextureToTexture(this.depth, this.depthSolid, 0, 0, 0, 0, 0, this.resolution, this.resolution);
		if (!drawing || !draws.hasTranslucent()) return;
		RenderPassDescriptor translucents = new RenderPassDescriptor(() -> "Afterburner shadow map translucents", kept,
			new RenderPassDescriptor.Attachment<>(this.depthView, OptionalDouble.empty()), bounds);
		try (RenderPass pass = encoder.createRenderPass(translucents)) {
			this.begin(pass, mc, terrain, atlas, bind);
			for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
				if (layer.translucent()) draws.draw(layer, pass, indexBuffer, indices.type());
			}
		}
		this.translucentsDrawn = true;
	}

	/** Sets up a pass drawing the terrain into the map. */
	private void begin(RenderPass pass, Minecraft mc, GpuBufferSlice terrain, GpuTextureView atlas, Consumer<RenderPass> bind) {
		((PackPass) pass).afterburner$setPackKind(PackPass.Kind.SHADOW);
		RenderSystem.bindDefaultUniforms(pass);
		pass.setUniform("Projection", this.projection);
		pass.setUniform("TerrainUniform", terrain);
		bind.accept(pass);
		pass.setUniform("Sampler0", atlas, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
		pass.setUniform("Sampler2", mc.gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
	}

	/**
	 * Entities' shadows (mobs, players, block entities, dropped items): the game's draws of them prepared for this frame
	 * ({@code features}: those it draws for the camera), drawn again into the map with the pack's shadow programs, as the sun sees
	 * them (TranslateTarget#SHADOW_FROM_VIEW), into shadowtex0 and shadowtex1. A kept map first gets back its terrain alone,
	 * without the last frame's.
	 */
	void renderEntities(FrameUniforms frame, FeatureRenderDispatcher.PreparedFrame features, Consumer<RenderPass> bind) {
		if (!frame.hasShadow || !this.valid) return;
		boolean any = !features.isEmpty();
		if (!any && !this.entitiesDrawn) return;
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		if (this.drawnEachFrame && !this.terrainSaved && !this.entitiesDrawn) {
			// The terrain is drawn again next frame, before its entities: nothing to go back to.
		} else if (!this.terrainSaved) {
			if (this.terrainCopies.isEmpty()) {
				this.terrainCopies.add(this.copyOf(this.depth));
				if (this.depthSolid != null) this.terrainCopies.add(this.copyOf(this.depthSolid));
				for (int b : this.buffers) this.terrainCopies.add(this.copyOf(this.colorViews[b].texture()));
			}
			for (GpuTexture[] c : this.terrainCopies) {
				encoder.copyTextureToTexture(c[0], c[1], 0, 0, 0, 0, 0, this.resolution, this.resolution);
			}
			this.terrainSaved = true;
		} else if (this.entitiesDrawn) {
			for (GpuTexture[] c : this.terrainCopies) {
				encoder.copyTextureToTexture(c[1], c[0], 0, 0, 0, 0, 0, this.resolution, this.resolution);
			}
		}
		this.entitiesDrawn = false;
		if (!any) return;

		List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors = new ArrayList<>();
		for (int b : this.buffers) colors.add(new RenderPassDescriptor.Attachment<>(this.colorViews[b], Optional.empty()));
		RenderPass.RenderArea area = new RenderPass.RenderArea(0, 0, this.resolution, this.resolution);
		this.drawEntities(new RenderPassDescriptor(() -> "Afterburner shadow map entities", colors,
			new RenderPassDescriptor.Attachment<>(this.depthView, OptionalDouble.empty()), area), PackPass.Kind.SHADOW, features, bind);
		this.entitiesDrawn = true;
		// And into shadowtex1: copied over when the map has no water or glass, else drawn again there, its colors left alone.
		if (this.depthSolid == null) return;
		if (!this.translucentsDrawn) {
			encoder.copyTextureToTexture(this.depth, this.depthSolid, 0, 0, 0, 0, 0, this.resolution, this.resolution);
			return;
		}
		this.drawEntities(new RenderPassDescriptor(() -> "Afterburner shadow map entities without translucents", colors,
			new RenderPassDescriptor.Attachment<>(this.depthSolidView, OptionalDouble.empty()), area), PackPass.Kind.SHADOW_DEPTH, features, bind);
	}

	private void drawEntities(RenderPassDescriptor descriptor, PackPass.Kind kind, FeatureRenderDispatcher.PreparedFrame features,
			Consumer<RenderPass> bind) {
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
			((PackPass) pass).afterburner$setPackKind(kind);
			RenderSystem.bindDefaultUniforms(pass);
			bind.accept(pass);
			// The entities drawn for the shadow alone too (ShadowEntities).
			ShadowEntities.drawing(true);
			try {
				features.executeSolid(pass);
			} finally {
				ShadowEntities.drawing(false);
			}
		}
	}

	/** How far from the camera entities cast shadows, in blocks. */
	double entityRange() {
		return this.entityRange;
	}

	/** A texture of the map, and one like it to keep a copy in. */
	private GpuTexture[] copyOf(GpuTexture t) {
		GpuTexture copy = RenderSystem.getDevice().createTexture(t.getLabel() + " terrain", USAGE, t.getFormat(), this.resolution, this.resolution, 1, 1);
		this.textures.add(copy);
		return new GpuTexture[] {t, copy};
	}

	/**
	 * Picks the sections in the shadow's box, from a box made bigger by how far the camera and sun may move until the next pick,
	 * so the list (and the chunk batcher's draws) stay the same for a while.
	 */
	private void pick(ViewArea area, Vec3 camera, Matrix4fc view) {
		long center = area.getCameraSectionPos().asLong();
		if (this.picked && area == this.pickedArea && center == this.pickedSection && camera.distanceToSqr(this.pickedX, this.pickedY, this.pickedZ) < MOVE * MOVE
				&& turnedLittle(view, this.pickedView)) {
			return;
		}
		this.picked = true;
		this.pickedArea = area;
		this.pickedSection = center;
		this.pickedX = camera.x;
		this.pickedY = camera.y;
		this.pickedZ = camera.z;
		this.pickedView.set(view);
		this.sections.clear();

		// Turning by TURN moves a point of the box by at most about 3 * TURN times its distance from the shadow's eye.
		double reach = Math.sqrt(2.0 * this.distance * this.distance + DEPTH * DEPTH);
		double margin = SECTION_RADIUS + MOVE + this.interval + 3.0 * TURN * reach;
		double side = this.distance + margin;
		double near = -0.05 + margin;
		double far = -DEPTH - margin;

		// The box's corners in the world give the sections to look at.
		Matrix4f inverse = new Matrix4f(view).invert();
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		Vector3f corner = new Vector3f();
		for (int i = 0; i < 8; i++) {
			inverse.transformPosition((float) ((i & 1) == 0 ? -side : side), (float) ((i & 2) == 0 ? -side : side), (float) ((i & 4) == 0 ? near : far), corner);
			minX = Math.min(minX, corner.x);
			minY = Math.min(minY, corner.y);
			minZ = Math.min(minZ, corner.z);
			maxX = Math.max(maxX, corner.x);
			maxY = Math.max(maxY, corner.y);
			maxZ = Math.max(maxZ, corner.z);
		}
		// And the sections of the voxel volume, the camera in its middle, as far as the camera may move until the next pick.
		int volX0 = Integer.MAX_VALUE, volX1 = Integer.MIN_VALUE, volY0 = Integer.MAX_VALUE, volY1 = Integer.MIN_VALUE;
		int volZ0 = Integer.MAX_VALUE, volZ1 = Integer.MIN_VALUE;
		if (this.volume != null) {
			volX0 = SectionPos.posToSectionCoord(camera.x - this.volume[0] * 0.5 - MOVE);
			volX1 = SectionPos.posToSectionCoord(camera.x + this.volume[0] * 0.5 + MOVE);
			volY0 = SectionPos.posToSectionCoord(camera.y - this.volume[1] * 0.5 - MOVE);
			volY1 = SectionPos.posToSectionCoord(camera.y + this.volume[1] * 0.5 + MOVE);
			volZ0 = SectionPos.posToSectionCoord(camera.z - this.volume[2] * 0.5 - MOVE);
			volZ1 = SectionPos.posToSectionCoord(camera.z + this.volume[2] * 0.5 + MOVE);
		}
		SectionPos middle = area.getCameraSectionPos();
		int radius = area.getViewDistance();
		int x0 = Math.max(Math.min(SectionPos.posToSectionCoord(camera.x + minX), volX0), middle.x() - radius);
		int x1 = Math.min(Math.max(SectionPos.posToSectionCoord(camera.x + maxX), volX1), middle.x() + radius);
		int y0 = Math.max(Math.min(SectionPos.posToSectionCoord(camera.y + minY), volY0), area.minSectionY());
		int y1 = Math.min(Math.max(SectionPos.posToSectionCoord(camera.y + maxY), volY1), area.maxSectionY());
		int z0 = Math.max(Math.min(SectionPos.posToSectionCoord(camera.z + minZ), volZ0), middle.z() - radius);
		int z1 = Math.min(Math.max(SectionPos.posToSectionCoord(camera.z + maxZ), volZ1), middle.z() + radius);

		// Then each section's middle, camera-relative like the terrain, against the box in the shadow's view.
		float m00 = view.m00(), m01 = view.m01(), m02 = view.m02();
		float m10 = view.m10(), m11 = view.m11(), m12 = view.m12();
		float m20 = view.m20(), m21 = view.m21(), m22 = view.m22();
		float m30 = view.m30(), m31 = view.m31(), m32 = view.m32();
		for (int sx = x0; sx <= x1; sx++) {
			double cx = sx * 16 + 8 - camera.x;
			for (int sz = z0; sz <= z1; sz++) {
				double cz = sz * 16 + 8 - camera.z;
				for (int sy = y0; sy <= y1; sy++) {
					double cy = sy * 16 + 8 - camera.y;
					double vx = m00 * cx + m10 * cy + m20 * cz + m30;
					double vy = m01 * cx + m11 * cy + m21 * cz + m31;
					double vz = m02 * cx + m12 * cy + m22 * cz + m32;
					boolean inVolume = sx >= volX0 && sx <= volX1 && sy >= volY0 && sy <= volY1 && sz >= volZ0 && sz <= volZ1;
					if (!inVolume && (Math.abs(vx) > side || Math.abs(vy) > side || vz > near || vz < far)) continue;
					SectionRenderDispatcher.RenderSection section = area.getRenderSectionAt(this.at.set(sx * 16, sy * 16, sz * 16));
					if (section != null) this.sections.add(section);
				}
			}
		}
	}

	/** Whether the rotation turned less than {@link #TURN} since the pick. */
	private static boolean turnedLittle(Matrix4fc a, Matrix4fc b) {
		return Math.abs(a.m00() - b.m00()) < TURN && Math.abs(a.m01() - b.m01()) < TURN && Math.abs(a.m02() - b.m02()) < TURN
			&& Math.abs(a.m10() - b.m10()) < TURN && Math.abs(a.m11() - b.m11()) < TURN && Math.abs(a.m12() - b.m12()) < TURN
			&& Math.abs(a.m20() - b.m20()) < TURN && Math.abs(a.m21() - b.m21()) < TURN && Math.abs(a.m22() - b.m22()) < TURN;
	}

	@Override
	public void close() {
		this.terrainCopies.clear();
		this.solidCopies.clear();
		ChunkBatcher.shadowFrame().clear();
		this.sections.clear();
		this.pickedArea = null;
		this.valid = false;
		for (GpuTextureView v : this.views) v.close();
		for (GpuTexture t : this.textures) t.close();
		this.views.clear();
		this.textures.clear();
		this.projection.close();
		MemoryUtil.memFree(this.projectionData);
	}
}
