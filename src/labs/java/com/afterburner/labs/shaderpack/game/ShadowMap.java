package com.afterburner.labs.shaderpack.game;

import com.afterburner.client.mixin.render.ViewAreaAccessor;
import com.afterburner.client.render.ChunkBatcher;
import com.afterburner.labs.mixin.shader.LevelRendererAccessor;
import com.afterburner.labs.shaderpack.PackLoader;
import com.afterburner.labs.shaderpack.PackUniforms;
import com.afterburner.labs.shaderpack.TranslateTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * The shadow map: the terrain as the sun (or moon) sees it, drawn at the start of a frame with the pack's shadow programs, or kept
 * from the frame before while nothing it shows changed ({@link #holds}, {@link Shaderpacks.ShadowUpdates}).
 * Its depth is shadowtex0, its colors shadowcolor0-1; shadowtex1 is its depth without water and glass (the terrain's is copied
 * before they're drawn, as with Iris), or shadowtex0 when the pack's shadowTranslucent is false and they cast no shadow.
 * <p>
 * The chunk batcher draws the solid and cutout sections inside the shadow's box (shadowDistance either side, 256 blocks
 * deep), and those in the pack's voxel volume around the camera (its 3D images, which packs fill from the shadow pass: colored
 * lighting) wherever the sun is. Entities near enough are drawn in every frame, over the terrain, into both depths
 * ({@link #renderEntities}), with the camera's view (TranslateTarget#SHADOW_FROM_VIEW turns it into the sun's).
 */
final class ShadowMap implements AutoCloseable {
	static final int COLORS = 2;
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
		"skyColor", "fogColor", TranslateTarget.SHADOW_FROM_VIEW, TranslateTarget.SHADOW_PROJECTION);
	final int resolution;
	private final float distance;
	private final float interval;
	/** How far from the camera entities cast shadows (the pack's shadowDistance times its entityShadowDistanceMul). */
	private final double entityRange;
	/** The pack's voxel volume (its biggest 3D image), in blocks around the camera, or null without one. */
	private final int @Nullable [] volume;
	/** The shadowcolor buffers the shadow programs draw, as the framebuffer's color attachments. */
	private final int[] buffers;
	private final Gl.Format[] formats = new Gl.Format[COLORS];
	private final boolean[] clear = new boolean[COLORS];
	private final Vector4f[] clearColors = new Vector4f[COLORS];
	private final List<Integer> textures = new ArrayList<>();
	private final int depth;
	/** shadowtex1, the depth before water and glass are drawn, or 0 when they aren't drawn (shadowTranslucent=false). */
	private final int depthSolid;
	private final int[] colors = new int[COLORS];
	/** The map, and the same with shadowtex1 for its depth (entities drawn again there, depth alone). */
	private final Gl.Framebuffer framebuffer;
	private final Gl.@Nullable Framebuffer solidFramebuffer;
	/** What's drawn into the map now, or null. */
	private PackPass.@Nullable Kind drawing;

	/** The sections picked last, and what for. */
	private final ObjectArrayList<SectionRenderDispatcher.RenderSection> sections = new ObjectArrayList<>();
	private final Matrix4f pickedView = new Matrix4f();
	private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
	private @Nullable ViewArea pickedArea;
	private boolean picked;
	private long pickedSection;
	private double pickedX, pickedY, pickedZ;

	/** This frame's sections are picked ({@link #holds}), whether there's anything to draw, and in water or glass. */
	private boolean prepared, any, anyTranslucent;
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
	 * shadowcolor buffers drawn, each beside its copy (with whether it's a depth).
	 */
	private final List<int[]> terrainCopies = new ArrayList<>();
	/** The map with the solid blocks alone, which a map {@link #refreshes refreshed} goes back to: the same, each beside its copy. */
	private final List<int[]> solidCopies = new ArrayList<>();
	/** Whether the copies hold the terrain as last drawn, and whether entities are drawn in the map. */
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
		// Somewhere to draw even if the programs only write depth.
		if (drawn.isEmpty()) drawn.add(0);
		this.buffers = drawn.stream().mapToInt(Integer::intValue).toArray();

		this.depth = this.keep(Gl.texture(Gl.Format.DEPTH, this.resolution, this.resolution, true, false));
		Gl.clearDepth(this.depth, 1.0F);
		if (pack.properties.getBoolean("shadowTranslucent", true)) {
			this.depthSolid = this.keep(Gl.texture(Gl.Format.DEPTH, this.resolution, this.resolution, true, false));
			Gl.clearDepth(this.depthSolid, 1.0F);
		} else {
			this.depthSolid = 0;
		}
		for (int b : this.buffers) {
			String format = setting(pack.consts, b, "Format");
			this.formats[b] = format == null ? Gl.Format.RGBA8 : Gl.format(format);
			this.clear[b] = !"false".equals(setting(pack.consts, b, "Clear"));
			String color = setting(pack.consts, b, "ClearColor");
			this.clearColors[b] = color != null ? PackTargets.vec4(color) : new Vector4f(1, 1, 1, 1);
			this.colors[b] = this.keep(Gl.texture(this.formats[b], this.resolution, this.resolution, false, false));
			// Those not cleared every frame start out cleared.
			Vector4f c = this.clearColors[b];
			Gl.clear(this.colors[b], this.formats[b], c.x, c.y, c.z, c.w);
		}
		int bound = Gl.drawFramebuffer();
		this.framebuffer = new Gl.Framebuffer().bind();
		for (int i = 0; i < this.buffers.length; i++) this.framebuffer.color(i, this.colors[this.buffers[i]]);
		this.framebuffer.depth(this.depth).drawBuffers(Gl.all(this.buffers.length));
		if (this.depthSolid != 0) {
			this.solidFramebuffer = new Gl.Framebuffer().bind();
			this.solidFramebuffer.depth(this.depthSolid).drawBuffers(new int[0]);
		} else {
			this.solidFramebuffer = null;
		}
		Gl.bindFramebuffer(bound);
	}

	/** shadowcolor0's settings may also be named shadowcolor's. */
	private static @Nullable String setting(Map<String, String> consts, int buffer, String what) {
		String value = consts.get("shadowcolor" + buffer + what);
		if (value == null && buffer == 0) value = consts.get("shadowcolor" + what);
		return value;
	}

	private int keep(int texture) {
		this.textures.add(texture);
		return texture;
	}

	int[] buffers() {
		return this.buffers;
	}

	Gl.Format format(int buffer) {
		return this.formats[buffer];
	}

	int depth() {
		return this.depth;
	}

	/** shadowtex1: the depth without water and glass. */
	int depthNoTranslucents() {
		return this.depthSolid != 0 ? this.depthSolid : this.depth;
	}

	/** A shadowcolor buffer, or 0 if the shadow programs don't draw it. */
	int color(int buffer) {
		return buffer >= 0 && buffer < COLORS ? this.colors[buffer] : 0;
	}

	/** The pack's images the shadow programs write, which a kept map keeps (they aren't cleared). */
	Set<String> written() {
		return this.written;
	}

	/** What's drawn into the map now if {@code framebuffer} (the one drawn into) is its, else null. */
	PackPass.@Nullable Kind drawing(int framebuffer) {
		PackPass.Kind kind = this.drawing;
		if (kind == null) return null;
		Gl.Framebuffer own = kind == PackPass.Kind.SHADOW_DEPTH ? this.solidFramebuffer : this.framebuffer;
		return own != null && own.id == framebuffer ? kind : null;
	}

	/** Points the outputs of the program drawing into the map now at its attachments (it's bound); the depth-only one has none. */
	void drawBuffers(PackPass.Kind kind, int[] attachments) {
		if (kind != PackPass.Kind.SHADOW_DEPTH) this.framebuffer.drawBuffers(attachments);
	}

	/**
	 * Whether the map drawn before still shows this frame's shadows, so it needn't be drawn again: the same terrain in it, the
	 * sun at the same step, the camera in the same shadowIntervalSize cell (the same block, if the programs write images, which
	 * packs fill around the camera's block) and the same values of what else the programs read. Picks this frame's sections
	 * either way. Asked before the pack's images are cleared.
	 */
	boolean holds(Minecraft mc, FrameUniforms frame, PackUniforms.Inputs inputs) {
		this.prepared = false;
		ViewArea area = ((LevelRendererAccessor) mc.levelRenderer).afterburner$viewArea();
		if (!frame.hasShadow || area == null || mc.level == null) return false;
		Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
		this.prepare(area, mc.level, camera, frame);
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
	void keep(Minecraft mc, FrameUniforms frame) {
		this.prepared = false;
		if (this.refreshes(this.drawnMode) && !this.solidCopies.isEmpty()) this.draw(mc, frame, false);
	}

	/** Something wasn't drawn into the map (a shadow program still compiling): draw it again next frame. */
	void invalidate() {
		this.valid = false;
	}

	/** Picks the sections, and sums up what they hold now: drawn again when that changes. */
	private void prepare(ViewArea area, ClientLevel level, Vec3 camera, FrameUniforms frame) {
		this.pick(area, level, camera, frame.shadowView);
		long contents = this.sections.size();
		boolean any = false, translucent = false;
		for (SectionRenderDispatcher.RenderSection section : this.sections) {
			SectionRenderDispatcher.CompiledSection compiled = section.getCompiled();
			// A section built again gets a new CompiledSection.
			contents = contents * 31 + System.identityHashCode(compiled);
			contents = contents * 31 + section.getOrigin().asLong();
			if (compiled.hasNoRenderableLayers()) continue;
			any = true;
			translucent |= !compiled.isEmpty(RenderType.translucent()) || !compiled.isEmpty(RenderType.tripwire());
		}
		this.contents = contents;
		this.any = any;
		this.anyTranslucent = translucent;
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

	/** Draws this frame's shadow map, before anything else is drawn: the terrain inside the shadow's box. */
	void render(Minecraft mc, FrameUniforms frame, PackUniforms.Inputs inputs) {
		ViewArea area = ((LevelRendererAccessor) mc.levelRenderer).afterburner$viewArea();
		if (!frame.hasShadow || area == null || mc.level == null) {
			this.valid = false;
			return;
		}
		Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
		if (!this.prepared) this.prepare(area, mc.level, camera, frame);
		this.prepared = false;
		this.remember(camera, frame, inputs);
		this.draw(mc, frame, true);
	}

	/**
	 * Draws the terrain into the map: in {@code full}, or over the solid blocks kept from when it was (the map
	 * {@link #refreshes}), which then get their copy.
	 */
	private void draw(Minecraft mc, FrameUniforms frame, boolean full) {
		this.terrainSaved = false;
		this.entitiesDrawn = false;
		boolean split = this.refreshes(this.drawnMode);
		this.drawnEachFrame = split || this.drawnMode == Shaderpacks.ShadowUpdates.EVERY_FRAME;
		Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
		if (full) {
			Gl.clearDepth(this.depth, 1.0F);
			for (int b : this.buffers) {
				Vector4f c = this.clearColors[b];
				if (this.clear[b]) Gl.clear(this.colors[b], this.formats[b], c.x, c.y, c.z, c.w);
			}
			if (this.any) this.drawLayers(frame, camera, split ? Layers.SOLID : Layers.OPAQUE);
			if (split) {
				if (this.solidCopies.isEmpty()) this.copies(this.solidCopies, false);
				for (int[] c : this.solidCopies) Gl.copy(c[0], c[1], this.resolution, this.resolution, c[2] != 0);
			}
		} else {
			for (int[] c : this.solidCopies) Gl.copy(c[1], c[0], this.resolution, this.resolution, c[2] != 0);
		}
		// Over the solid blocks kept, the rest of what isn't see-through (leaves, grass): this frame's waving.
		if (split && this.any) this.drawLayers(frame, camera, Layers.CUTOUTS);
		// Water and glass go in after shadowtex1 got the map without them.
		this.translucentsDrawn = false;
		if (this.depthSolid == 0) return;
		Gl.copy(this.depth, this.depthSolid, this.resolution, this.resolution, true);
		if (!this.any || !this.anyTranslucent) return;
		this.drawLayers(frame, camera, Layers.TRANSLUCENT);
		this.translucentsDrawn = true;
	}

	private enum Layers {
		SOLID, CUTOUTS, OPAQUE, TRANSLUCENT;

		boolean has(RenderType type) {
			boolean translucent = type == RenderType.translucent() || type == RenderType.tripwire();
			return switch (this) {
				case SOLID -> type == RenderType.solid();
				case CUTOUTS -> !translucent && type != RenderType.solid();
				case OPAQUE -> !translucent;
				case TRANSLUCENT -> translucent;
			};
		}
	}

	/** Draws the picked sections' layers into the map, through the chunk batcher, with the game's render state for each. */
	private void drawLayers(FrameUniforms frame, Vec3 camera, Layers which) {
		this.begin(PackPass.Kind.SHADOW, this.framebuffer);
		try {
			for (RenderType type : ChunkBatcher.LAYERS) {
				if (!which.has(type)) continue;
				type.setupRenderState();
				try {
					ChunkBatcher.drawShadow(type, this.sections, camera.x, camera.y, camera.z, frame.shadowView, frame.shadowProjection);
				} finally {
					type.clearRenderState();
				}
			}
		} finally {
			this.end();
		}
	}

	private int bound;

	/** Draws into the map from now ({@code kind}: SHADOW, or SHADOW_DEPTH into shadowtex1). */
	private void begin(PackPass.Kind kind, Gl.Framebuffer target) {
		this.bound = Gl.drawFramebuffer();
		this.drawing = kind;
		target.bind();
		Gl.viewport(this.resolution, this.resolution);
	}

	/** Back to what was drawn into before {@link #begin}: the caller sets the viewport again. */
	private void end() {
		this.drawing = null;
		Gl.bindFramebuffer(this.bound);
		// The shadow programs draw both sides.
		RenderSystem.enableCull();
	}

	/**
	 * Entities' shadows (mobs, players, block entities, dropped items) near enough to the camera, drawn into the map with the
	 * pack's shadow programs, into shadowtex0 and shadowtex1, every frame. A kept map first gets back its terrain alone,
	 * without the last frame's. With the camera's view, as the game draws them (the programs turn it into the sun's).
	 */
	void renderEntities(Minecraft mc, FrameUniforms frame, DeltaTracker delta, Matrix4fc view) {
		if (!frame.hasShadow || !this.valid || mc.level == null) return;
		Camera camera = mc.gameRenderer.getMainCamera();
		List<Entity> entities = this.entities(mc.level, camera);
		List<BlockEntity> blockEntities = this.blockEntities(camera);
		boolean any = !entities.isEmpty() || !blockEntities.isEmpty();
		if (!any && !this.entitiesDrawn) return;
		if (this.drawnEachFrame && !this.terrainSaved && !this.entitiesDrawn) {
			// The terrain is drawn again next frame, before its entities: nothing to go back to.
		} else if (!this.terrainSaved) {
			if (this.terrainCopies.isEmpty()) this.copies(this.terrainCopies, true);
			for (int[] c : this.terrainCopies) Gl.copy(c[0], c[1], this.resolution, this.resolution, c[2] != 0);
			this.terrainSaved = true;
		} else if (this.entitiesDrawn) {
			for (int[] c : this.terrainCopies) Gl.copy(c[1], c[0], this.resolution, this.resolution, c[2] != 0);
		}
		this.entitiesDrawn = false;
		if (!any) return;

		this.drawEntities(mc, delta, view, entities, blockEntities, PackPass.Kind.SHADOW, this.framebuffer);
		this.entitiesDrawn = true;
		// And into shadowtex1: copied over when the map has no water or glass, else drawn again there, its colors left alone.
		if (this.solidFramebuffer == null) return;
		if (!this.translucentsDrawn) {
			Gl.copy(this.depth, this.depthSolid, this.resolution, this.resolution, true);
			return;
		}
		this.drawEntities(mc, delta, view, entities, blockEntities, PackPass.Kind.SHADOW_DEPTH, this.solidFramebuffer);
	}

	/** The entities casting shadows: within range, the camera's own too (your own shadow), not other players of this client. */
	private List<Entity> entities(ClientLevel level, Camera camera) {
		List<Entity> out = new ArrayList<>();
		if (this.entityRange <= 0.0) return out;
		Vec3 at = camera.getPosition();
		double reach = this.entityRange * this.entityRange;
		for (Entity entity : level.entitiesForRendering()) {
			if (entity.tickCount == 0 || entity.distanceToSqr(at) > reach) continue;
			if (entity instanceof LocalPlayer && camera.getEntity() != entity) continue;
			if (entity != camera.getEntity() && !entity.shouldRender(at.x, at.y, at.z)) continue;
			out.add(entity);
		}
		return out;
	}

	/** The block entities of the picked sections within range. */
	private List<BlockEntity> blockEntities(Camera camera) {
		List<BlockEntity> out = new ArrayList<>();
		if (this.entityRange <= 0.0) return out;
		Vec3 at = camera.getPosition();
		double reach = (this.entityRange + SECTION_RADIUS) * (this.entityRange + SECTION_RADIUS);
		double blockReach = this.entityRange * this.entityRange;
		for (SectionRenderDispatcher.RenderSection section : this.sections) {
			BlockPos origin = section.getOrigin();
			if (at.distanceToSqr(origin.getX() + 8, origin.getY() + 8, origin.getZ() + 8) > reach) continue;
			for (BlockEntity be : section.getCompiled().getRenderableBlockEntities()) {
				BlockPos p = be.getBlockPos();
				if (at.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) <= blockReach) out.add(be);
			}
		}
		return out;
	}

	private void drawEntities(Minecraft mc, DeltaTracker delta, Matrix4fc view, List<Entity> entities, List<BlockEntity> blockEntities,
			PackPass.Kind kind, Gl.Framebuffer target) {
		Camera camera = mc.gameRenderer.getMainCamera();
		Vec3 at = camera.getPosition();
		EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
		BlockEntityRenderDispatcher blocks = mc.getBlockEntityRenderDispatcher();
		MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
		TickRateManager ticks = mc.level.tickRateManager();
		Matrix4fStack modelView = RenderSystem.getModelViewStack();
		modelView.pushMatrix();
		modelView.set(view);
		RenderSystem.applyModelViewMatrix();
		buffers.endBatch();
		// The game's round shadows under them aren't drawn into the sun's.
		dispatcher.setRenderShadow(false);
		this.begin(kind, target);
		PoseStack pose = new PoseStack();
		try {
			for (Entity entity : entities) {
				float partial = delta.getGameTimeDeltaPartialTick(!ticks.isEntityFrozen(entity));
				double x = Mth.lerp(partial, entity.xOld, entity.getX());
				double y = Mth.lerp(partial, entity.yOld, entity.getY());
				double z = Mth.lerp(partial, entity.zOld, entity.getZ());
				float yaw = Mth.lerp(partial, entity.yRotO, entity.getYRot());
				dispatcher.render(entity, x - at.x, y - at.y, z - at.z, yaw, partial, pose, buffers, dispatcher.getPackedLightCoords(entity, partial));
			}
			float partial = delta.getGameTimeDeltaPartialTick(false);
			for (BlockEntity be : blockEntities) {
				BlockPos p = be.getBlockPos();
				pose.pushPose();
				pose.translate(p.getX() - at.x, p.getY() - at.y, p.getZ() - at.z);
				blocks.render(be, partial, pose, buffers);
				pose.popPose();
			}
			buffers.endBatch();
		} catch (RuntimeException e) {
			// An entity that doesn't draw outside the game's own pass casts no shadow; what was drawn so far stays.
			buffers.endBatch();
			this.valid = false;
		} finally {
			this.end();
			dispatcher.setRenderShadow(true);
			modelView.popMatrix();
			RenderSystem.applyModelViewMatrix();
		}
	}

	/** How far from the camera entities cast shadows, in blocks. */
	double entityRange() {
		return this.entityRange;
	}

	/** Copies of the map's textures to go back to: the depth (with shadowtex1's for {@code solid}), then the colors drawn. */
	private void copies(List<int[]> into, boolean solid) {
		into.add(this.copyOf(this.depth, Gl.Format.DEPTH, true));
		if (solid && this.depthSolid != 0) into.add(this.copyOf(this.depthSolid, Gl.Format.DEPTH, true));
		for (int b : this.buffers) into.add(this.copyOf(this.colors[b], this.formats[b], false));
	}

	/** A texture of the map, and one like it to keep a copy in, and 1 if it's a depth. */
	private int[] copyOf(int texture, Gl.Format format, boolean depth) {
		int copy = this.keep(Gl.texture(format, this.resolution, this.resolution, depth, false));
		return new int[] {texture, copy, depth ? 1 : 0};
	}

	/**
	 * Picks the sections in the shadow's box, from a box made bigger by how far the camera and sun may move until the next pick,
	 * so the list stays the same for a while.
	 */
	private void pick(ViewArea area, ClientLevel level, Vec3 camera, Matrix4fc view) {
		SectionPos middle = SectionPos.of(camera);
		long center = middle.asLong();
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
		// The view area's grid wraps around: past its radius a section is another place's.
		ViewAreaAccessor grid = (ViewAreaAccessor) area;
		int radiusX = (grid.afterburner$sizeX() - 1) / 2, radiusZ = (grid.afterburner$sizeZ() - 1) / 2;
		int x0 = Math.max(Math.min(SectionPos.posToSectionCoord(camera.x + minX), volX0), middle.x() - radiusX);
		int x1 = Math.min(Math.max(SectionPos.posToSectionCoord(camera.x + maxX), volX1), middle.x() + radiusX);
		int y0 = Math.max(Math.min(SectionPos.posToSectionCoord(camera.y + minY), volY0), level.getMinSection());
		int y1 = Math.min(Math.max(SectionPos.posToSectionCoord(camera.y + maxY), volY1), level.getMaxSection() - 1);
		int z0 = Math.max(Math.min(SectionPos.posToSectionCoord(camera.z + minZ), volZ0), middle.z() - radiusZ);
		int z1 = Math.min(Math.max(SectionPos.posToSectionCoord(camera.z + maxZ), volZ1), middle.z() + radiusZ);

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
					SectionRenderDispatcher.RenderSection section = grid.afterburner$sectionAt(this.at.set(sx * 16, sy * 16, sz * 16));
					if (section != null && section.getOrigin().equals(this.at)) this.sections.add(section);
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
		this.sections.clear();
		this.pickedArea = null;
		this.valid = false;
		this.framebuffer.close();
		if (this.solidFramebuffer != null) this.solidFramebuffer.close();
		for (int t : this.textures) Gl.deleteTexture(t);
		this.textures.clear();
	}
}
