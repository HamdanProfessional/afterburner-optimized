package com.afterburner.labs.shaderpack.game;

import com.afterburner.client.render.ChunkBatcher;
import com.afterburner.labs.lod.Lod;
import com.afterburner.labs.mixin.shader.ShaderInstanceAccessor;
import com.afterburner.labs.shaderpack.AlphaCutoff;
import com.afterburner.labs.shaderpack.GlslTranslator;
import com.afterburner.labs.shaderpack.PackBlending;
import com.afterburner.labs.shaderpack.PackLoader;
import com.afterburner.labs.shaderpack.TranslateTarget;
import com.afterburner.labs.shaderpack.UniformLayout;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pack's programs, made for the game's shaders. A game shader drawing into the pack's buffers is applied as usual, then its
 * program is swapped for the pack's made for it (see {@link PackRenderer#applied}): for its vertex format and the uniforms it
 * has, which the game goes on setting (its Uniform objects are pointed at the pack program's locations while it's in use).
 * <p>
 * World programs are made when first drawn, without stopping the game: with KHR_parallel_shader_compile the driver compiles
 * them in the background, and until they're ready what they draw is left out (a new kind of mob or particle shows a frame or
 * two late). At most {@link #FINISH_BUDGET} of a frame goes to making them, past the first one each frame.
 */
final class PackPipelines implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger("afterburner");

	/** Render thread time per frame for making world programs, in nanoseconds. */
	private static final long FINISH_BUDGET = 3_000_000L;
	/** Vanilla's alpha cutout in a fragment shader: "if (color.a < 0.1) { discard; }". */
	private static final Pattern CUTOUT = Pattern.compile("\\.a\\s*<\\s*([0-9.]+)\\s*\\)\\s*\\{?\\s*discard");
	/** A fragment shader's cutout by its name, "" for none (read from the resources once). */
	private static final Map<String, String> CUTOUTS = new ConcurrentHashMap<>();
	/** A program that draws nothing: its vertices land outside the screen. */
	private static final String NOTHING_VERTEX = "#version 330 core\nvoid main() {\n\tgl_Position = vec4(2.0, 2.0, 2.0, 1.0);\n}\n";
	private static final String NOTHING_FRAGMENT = "#version 330 core\nvoid main() {\n}\n";

	/** A compiled fullscreen program. */
	record Fullscreen(String name, Gl.Program program, int[] buffers, int[] slots, @Nullable Optional<PackBlending.Factors> blend,
			Optional<PackBlending.Factors> @Nullable [] perBuffer) {}

	/** One of the pack's programs made for one way of drawing (a game shader's format and uniforms, a pass). */
	static final class World {
		final String label;
		final Gl.@Nullable Program program;
		/** The game uniforms it was made with: their locations once it's linked, -1 for those it doesn't read. */
		final List<String> uniforms;
		int @Nullable [] uniformLocations;
		/** The slots ({@link PackSlots}) of the pack's samplers it reads. */
		final int[] slots;
		/** Which attachment each fragment output location writes, -1 for none. */
		final int[] drawBuffers;
		final @Nullable Optional<PackBlending.Factors> blend;
		final Optional<PackBlending.Factors> @Nullable [] perAttachment;
		/** Its render stage (renderStage), by its number. */
		final int stage;
		/** It didn't compile: what it draws is left out. */
		boolean failed;

		World(String label, Gl.@Nullable Program program, List<String> uniforms, int[] slots, int[] drawBuffers,
				@Nullable Optional<PackBlending.Factors> blend, Optional<PackBlending.Factors> @Nullable [] perAttachment, int stage) {
			this.label = label;
			this.program = program;
			this.uniforms = uniforms;
			this.slots = slots;
			this.drawBuffers = drawBuffers;
			this.blend = blend;
			this.perAttachment = perAttachment;
			this.stage = stage;
		}
	}

	/** What a game shader draws with in one context: the pack's program, nothing, or (the pack has no program for it) its own. */
	static final class Use {
		/** Null: the game's own program draws (into colortex0), see {@link #VANILLA}. */
		final @Nullable World world;
		/** The pack program's location for each of the shader's Uniforms, once linked. */
		int @Nullable [] locations;

		Use(@Nullable World world) {
			this.world = world;
		}
	}

	/** Nothing is drawn. */
	static final World NONE = new World("", null, List.of(), new int[0], new int[0], null, null, 0);
	private static final Use NOTHING = new Use(NONE);
	/** The game's shader draws as it is: the pack has no program at all for it (not even gbuffers_basic). */
	static final Use VANILLA = new Use(null);

	/** The uses kept on a game shader, for one PackPipelines. */
	private record Uses(PackPipelines owner, Int2ObjectOpenHashMap<Use> byKey) {}

	private final PackLoader.Loaded pack;
	private final PackTargets targets;
	private final @Nullable ShadowMap shadow;
	private final PackSlots slots;
	private final Map<String, World> worlds = new HashMap<>();
	private final List<Gl.Program> compiled = new ArrayList<>();
	private final Set<String> failed = new LinkedHashSet<>();
	private Gl.@Nullable Program nothing;
	private long finishing;
	private boolean madeOne;
	/** A shadow draw was left out this frame, its program not ready yet. */
	private boolean shadowWaited;
	private @Nullable World farTerrain, farWater;

	/** {@code shadow} is null when the shadow map isn't drawn: then nothing is drawn in a shadow pass. */
	PackPipelines(PackLoader.Loaded pack, PackTargets targets, @Nullable ShadowMap shadow, PackSlots slots) {
		this.pack = pack;
		this.targets = targets;
		this.shadow = shadow;
		this.slots = slots;
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

	/** Each frame, before anything is drawn. */
	void beginFrame() {
		this.finishing = 0L;
		this.madeOne = false;
		this.shadowWaited = false;
	}

	/** Whether a shadow draw was left out this frame because its program wasn't ready: the shadow map is missing it. */
	boolean shadowWaited() {
		return this.shadowWaited;
	}

	/** The program that draws nothing. */
	Gl.Program nothing() {
		if (this.nothing == null) {
			this.nothing = Gl.program("Afterburner nothing", NOTHING_VERTEX, NOTHING_FRAGMENT, new String[0], l -> "nothing:" + l,
				l -> "nothing:" + l, null);
		}
		return this.nothing;
	}

	/** Whether there's time left this frame to make or finish a program (always for the first). */
	private boolean budget() {
		return !this.madeOne || this.finishing < FINISH_BUDGET;
	}

	/** Whether a world program can be drawn with now, finishing it if it's done compiling and there's time. */
	boolean ready(World w) {
		Gl.Program p = w.program;
		if (p == null || w.failed) return false;
		if (w.uniformLocations != null) return true;
		if (!p.done() || !this.budget()) return false;
		long start = System.nanoTime();
		try {
			if (!p.ready()) {
				w.failed = true;
				this.fail(w.label, String.valueOf(p.error()));
				return false;
			}
			int[] locations = new int[w.uniforms.size()];
			for (int i = 0; i < locations.length; i++) locations[i] = p.location(w.uniforms.get(i));
			w.uniformLocations = locations;
			return true;
		} finally {
			this.finishing += System.nanoTime() - start;
			this.madeOne = true;
		}
	}

	// ---- The game's shaders ----

	/**
	 * What a game shader draws with: the pack's program for it ({@link Use#world}, maybe not ready yet), {@link #NOTHING}'s, or
	 * {@link #VANILLA}. {@code format} is the chunk batcher's ({@link ChunkBatcher#drawingFormat}), {@code blend} whether it
	 * blends.
	 */
	Use use(ShaderInstance shader, PackPass.Kind kind, Shaderpacks.Phase phase, int format, boolean blend, Shaderpacks.SkyStage sky) {
		PackShader holder = (PackShader) shader;
		Object kept = holder.afterburner$packUses();
		Uses uses = kept instanceof Uses u && u.owner == this ? u : null;
		if (uses == null) {
			uses = new Uses(this, new Int2ObjectOpenHashMap<>());
			holder.afterburner$setPackUses(uses);
		}
		int skyKey = kind == PackPass.Kind.SKY ? sky.ordinal() : 0;
		int key = kind.ordinal() | phase.ordinal() << 3 | Math.max(0, format) << 8 | (blend ? 1 : 0) << 10 | skyKey << 11;
		Use use = uses.byKey.get(key);
		if (use == null) {
			if (!this.budget()) {
				if (kind == PackPass.Kind.SHADOW) this.shadowWaited = true;
				return NOTHING;
			}
			long start = System.nanoTime();
			try {
				use = this.makeUse(shader, kind, phase, Math.max(0, format), blend, sky);
			} finally {
				this.finishing += System.nanoTime() - start;
				this.madeOne = true;
			}
			uses.byKey.put(key, use);
		}
		World w = use.world;
		if (w != null && w != NONE && use.locations == null) {
			if (!this.ready(w)) {
				if (kind == PackPass.Kind.SHADOW && !w.failed) this.shadowWaited = true;
				return NOTHING;
			}
			use.locations = locations(shader, w);
		}
		return use;
	}

	/** The pack program's location for each of a shader's Uniforms (by name), -1 for those it doesn't read. */
	private static int[] locations(ShaderInstance shader, World w) {
		List<Uniform> uniforms = ((ShaderInstanceAccessor) shader).afterburner$uniforms();
		int[] out = new int[uniforms.size()];
		int[] own = w.uniformLocations;
		for (int i = 0; i < out.length; i++) {
			int at = w.uniforms.indexOf(uniforms.get(i).getName());
			out[i] = at >= 0 && own != null ? own[at] : -1;
		}
		return out;
	}

	private Use makeUse(ShaderInstance shader, PackPass.Kind kind, Shaderpacks.Phase phase, int format, boolean blend, Shaderpacks.SkyStage sky) {
		ShadowMap shadow = kind.shadow() ? this.shadow : null;
		if (kind.shadow() && shadow == null) return NOTHING;
		String path = ProgramMapping.shaderPath(shader.getName(), phase, blend, sky);
		// With the pack's shadows, entities cast their own (ShadowMap#renderEntities): the game's round one under them is left out.
		if (this.shadow != null && path.equals("entity_shadow")) return NOTHING;
		VertexFormat vertexFormat = shader.getVertexFormat();
		boolean textured = vertexFormat.getElementAttributeNames().contains("UV0");
		String name = ProgramMapping.program(path, kind, blend, textured);
		if (name == null) return NOTHING;
		PackLoader.Program program = this.pack.get(name);
		if (program == null) return kind.shadow() ? NOTHING : VANILLA;

		ShaderInstanceAccessor access = (ShaderInstanceAccessor) shader;
		List<String> samplerNames = access.afterburner$samplerNames();
		List<String> uniforms = new ArrayList<>();
		for (Uniform u : access.afterburner$uniforms()) {
			if (u.getLocation() != -1) uniforms.add(u.getName());
		}
		String key = shader.getName() + "|" + samplerNames + "|" + uniforms + "|" + kind + "|" + path + "|" + format + "|" + blend + "|"
			+ (kind == PackPass.Kind.SKY ? sky : "");
		World world = this.worlds.get(key);
		if (world == null) {
			Map<String, String> inputs = new LinkedHashMap<>();
			String[] attributes;
			if (format != ChunkBatcher.VANILLA && uniforms.contains("ChunkOffset")) {
				// The batcher's region formats (CompactVertices): their attributes at these locations.
				inputs.put("Position", "uvec4");
				inputs.put("Color", "vec4");
				inputs.put("UV0", "vec2");
				if (format == ChunkBatcher.EXTENDED) {
					inputs.put("AbMidTex", "vec2");
					inputs.put("AbNormal", "vec4");
					inputs.put("AbBlock", "uvec2");
				}
				attributes = inputs.keySet().toArray(new String[0]);
			} else {
				List<String> names = vertexFormat.getElementAttributeNames();
				List<VertexFormatElement> elements = vertexFormat.getElements();
				attributes = names.toArray(new String[0]);
				for (int i = 0; i < names.size(); i++) inputs.putIfAbsent(names.get(i), glslType(names.get(i), elements.get(i)));
			}
			String label = program.name() + " for " + shader.getName() + (format != ChunkBatcher.VANILLA ? " (" + format + ")" : "");
			int stage = kind == PackPass.Kind.SKY && sky != Shaderpacks.SkyStage.NONE ? ProgramMapping.stage(sky.stage)
				: ProgramMapping.stage(path, kind, blend);
			String cutout = cutout(shader);
			Map<String, Integer> vanillaUnits = new HashMap<>();
			for (int i = 0; i < samplerNames.size(); i++) vanillaUnits.put(samplerNames.get(i), i);
			world = this.make(label, name, path, program, kind, blend, inputs, uniforms, attributes, cutout, vanillaUnits, stage, shadow);
			this.worlds.put(key, world);
		}
		return new Use(world);
	}

	/** The pack's dh_terrain for Afterburner's far terrain (see Lod#drawForPack), or null when it isn't ready (or can't be). */
	@Nullable World farTerrain() {
		if (this.farTerrain == null) this.farTerrain = this.makeFar("far_terrain", PackPass.Kind.WORLD, false);
		return this.ready(this.farTerrain) ? this.farTerrain : null;
	}

	/** The pack's dh_water for the far terrain's water, drawn after the deferred passes in {@code kind}. */
	@Nullable World farWater(PackPass.Kind kind) {
		if (this.farWater == null) this.farWater = this.makeFar("far_water", kind, true);
		return this.ready(this.farWater) ? this.farWater : null;
	}

	private World makeFar(String path, PackPass.Kind kind, boolean blend) {
		String name = ProgramMapping.program(path, kind, blend, false);
		PackLoader.Program program = name == null ? null : this.pack.get(name);
		if (program == null) return NONE;
		Map<String, String> inputs = new LinkedHashMap<>();
		inputs.put("Position", "uint");
		inputs.put("Color", "vec4");
		List<String> uniforms = new ArrayList<>(TranslateTarget.FAR_UNIFORMS);
		return this.make(program.name() + " for the far terrain", name, path, program, kind, blend, inputs, uniforms, Lod.PACK_ATTRIBUTES, null,
			Map.of(), ProgramMapping.stage(path, kind, blend), null);
	}

	/**
	 * Translates a pack program for a way of drawing and starts compiling it. {@code vanillaUnits} are the game shader's
	 * samplers by unit (Sampler0 on 0, ...), which the game binds; the pack's own samplers get slots.
	 */
	private World make(String label, String slotName, String path, PackLoader.Program program, PackPass.Kind kind, boolean blend,
			Map<String, String> inputs, List<String> uniforms, String[] attributes, @Nullable String cutout, Map<String, Integer> vanillaUnits,
			int stage, @Nullable ShadowMap shadow) {
		int[] attachments = shadow != null ? shadow.buffers() : buffers(this.pack, kind);
		TranslateTarget target = TranslateTarget.world(shadow != null ? TranslateTarget.Kind.SHADOW : TranslateTarget.Kind.GBUFFERS, inputs, uniforms,
			kind == PackPass.Kind.HAND, Map.of(), attachments);
		// What the game cuts out (leaves, grass, item edges), OptiFine's alpha test does for the pack; old programs count on it.
		// The pack's own for the program, else where the game cuts out.
		AlphaCutoff test = this.pack.alphaTests.get(program.name());
		if (test == null) test = ProgramMapping.alphaTest(path, kind, cutout);
		target = target.withAlphaTest(test);
		GlslTranslator.Program translated;
		try {
			translated = this.pack.translate(program, target);
		} catch (GlslTranslator.TranslateException e) {
			this.fail(label, e.getMessage());
			return NONE;
		}

		// Samplers: the game's (Sampler0, Sampler2) on the units the game binds them to; the rest on our slots.
		Map<String, Integer> units = new LinkedHashMap<>();
		IntArrayList slotList = new IntArrayList();
		for (String sampler : translated.samplers) {
			Integer unit = vanillaUnits.get(sampler);
			if (unit == null) {
				int slot = this.slots.slot(sampler);
				if (!PackSlots.fits(slot)) {
					this.fail(label, "reads more textures than this GPU has units for");
					return NONE;
				}
				unit = PackSlots.unit(slot);
				// Images' samplers are bound by PackStorage, once a frame.
				ExternalBindings.Slot external = ExternalBindings.get(sampler);
				if (external == null || external.kind() != ExternalBindings.Kind.SAMPLER) slotList.add(slot);
			}
			units.put(sampler, unit);
		}

		int[] drawBuffers = new int[attachments.length];
		for (int a = 0; a < attachments.length; a++) {
			boolean written = false;
			for (int b : translated.drawBuffers) written |= b == attachments[a];
			drawBuffers[a] = written && kind != PackPass.Kind.SHADOW_DEPTH ? a : -1;
		}
		// The pack's blend directives (those for single buffers name colortex ones, not the shadow map's).
		Optional<PackBlending.Factors> all = this.pack.blending.blend(slotName, program.name(), -1);
		Optional<PackBlending.Factors>[] perAttachment = null;
		if (shadow == null) {
			@SuppressWarnings("unchecked")
			Optional<PackBlending.Factors>[] per = new Optional[attachments.length];
			boolean differs = false;
			for (int a = 0; a < attachments.length; a++) {
				Optional<PackBlending.Factors> own = this.pack.blending.blend(slotName, program.name(), attachments[a]);
				if (own != null && !own.equals(all)) {
					per[a] = own;
					differs = true;
				}
			}
			if (differs) perAttachment = per;
		}

		Gl.Program p = Gl.program(label, translated.vertex, translated.fragment, attributes, l -> translated.origin(GlslTranslator.Stage.VERTEX, l),
			l -> translated.origin(GlslTranslator.Stage.FRAGMENT, l), linked -> {
				for (Map.Entry<String, Integer> e : units.entrySet()) linked.uniform(e.getKey(), e.getValue());
				linked.block(UniformLayout.FRAME_BLOCK, Gl.FRAME_BINDING);
				linked.block(UniformLayout.DRAW_BLOCK, Gl.DRAW_BINDING);
				ExternalBindings.setup(linked);
			});
		this.compiled.add(p);
		return new World(label, p, uniforms, slotList.toIntArray(), drawBuffers, all, perAttachment, stage);
	}

	/** The GLSL type the game's shaders read a vertex element as. */
	private static String glslType(String name, VertexFormatElement e) {
		return switch (name) {
			case "Position", "Normal" -> "vec3";
			case "Color" -> "vec4";
			case "UV0" -> "vec2";
			case "UV1", "UV2" -> "ivec2";
			default -> {
				boolean integer = e.usage() == VertexFormatElement.Usage.UV && e.type() != VertexFormatElement.Type.FLOAT;
				int n = e.count();
				yield n == 1 ? (integer ? "int" : "float") : (integer ? "ivec" : "vec") + n;
			}
		};
	}

	/** Where the game's fragment shader cuts out ("0.1"), or null: read from its source. */
	private static @Nullable String cutout(ShaderInstance shader) {
		String name = shader.getFragmentProgram().getName();
		String found = CUTOUTS.computeIfAbsent(name, n -> {
			try {
				ResourceLocation id = ResourceLocation.parse(n);
				ResourceLocation file = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "shaders/core/" + id.getPath() + ".fsh");
				try (BufferedReader in = Minecraft.getInstance().getResourceManager().openAsReader(file)) {
					String source = in.lines().collect(Collectors.joining("\n"));
					Matcher m = CUTOUT.matcher(source);
					return m.find() ? m.group(1) : "";
				}
			} catch (IOException | RuntimeException e) {
				return "";
			}
		});
		return found.isEmpty() ? null : found;
	}

	// ---- Fullscreen programs ----

	/** A prepare, deferred or composite program drawing into its buffers; for final ({@code last}), into the screen. */
	@Nullable Fullscreen fullscreen(PackLoader.Program program, boolean last) {
		int[] buffers = last ? new int[] {0} : program.drawBuffers();
		IntArrayList kept = new IntArrayList();
		for (int b : buffers) if (last || this.targets.has(b)) kept.add(b);
		buffers = kept.toIntArray();
		if (buffers.length == 0) return null;
		GlslTranslator.Program translated;
		try {
			translated = this.pack.translate(program, TranslateTarget.fullscreen(buffers));
		} catch (GlslTranslator.TranslateException e) {
			this.fail(program.name(), e.getMessage());
			return null;
		}
		Map<String, Integer> units = new LinkedHashMap<>();
		IntArrayList slotList = new IntArrayList();
		for (String sampler : translated.samplers) {
			int slot = this.slots.slot(sampler);
			if (!PackSlots.fits(slot)) {
				this.fail(program.name(), "reads more textures than this GPU has units for");
				return null;
			}
			units.put(sampler, PackSlots.unit(slot));
			ExternalBindings.Slot external = ExternalBindings.get(sampler);
			if (external == null || external.kind() != ExternalBindings.Kind.SAMPLER) slotList.add(slot);
		}
		Gl.Program p = Gl.program(program.name(), translated.vertex, translated.fragment, new String[0],
			l -> translated.origin(GlslTranslator.Stage.VERTEX, l), l -> translated.origin(GlslTranslator.Stage.FRAGMENT, l), linked -> {
				for (Map.Entry<String, Integer> e : units.entrySet()) linked.uniform(e.getKey(), e.getValue());
				linked.block(UniformLayout.FRAME_BLOCK, Gl.FRAME_BINDING);
				linked.block(UniformLayout.DRAW_BLOCK, Gl.DRAW_BINDING);
				ExternalBindings.setup(linked);
			});
		this.compiled.add(p);
		if (!p.ready()) {
			this.fail(program.name(), String.valueOf(p.error()));
			return null;
		}
		// No blending, unless the pack's directives say so (blend.composite5=ONE ONE ONE ONE adds onto what's there).
		Optional<PackBlending.Factors> all = this.pack.blending.blend(program.name(), program.name(), -1);
		Optional<PackBlending.Factors>[] perBuffer = null;
		if (!last) {
			@SuppressWarnings("unchecked")
			Optional<PackBlending.Factors>[] per = new Optional[buffers.length];
			boolean differs = false;
			for (int a = 0; a < buffers.length; a++) {
				Optional<PackBlending.Factors> own = this.pack.blending.blend(program.name(), program.name(), buffers[a]);
				if (own != null && !own.equals(all)) {
					per[a] = own;
					differs = true;
				}
			}
			if (differs) perBuffer = per;
		}
		return new Fullscreen(program.name(), p, buffers, slotList.toIntArray(), all != null ? all : Optional.empty(), perBuffer);
	}

	private void fail(String label, String why) {
		if (this.failed.add(label)) LOGGER.warn("[Afterburner] Shader pack: {}: {}", label, why);
	}

	@Override
	public void close() {
		for (Gl.Program p : this.compiled) p.close();
		this.compiled.clear();
		if (this.nothing != null) this.nothing.close();
		this.nothing = null;
		this.worlds.clear();
	}

	// ---- The game's uniforms pointed at the pack's program ----

	private static @Nullable List<Uniform> swapped;
	private static int[] savedLocations = new int[16];
	private static int[] nowhere = new int[0];

	/** Locations for all of a shader's Uniforms that set nothing: for the program that draws nothing. */
	static int[] nowhere(ShaderInstance shader) {
		int n = ((ShaderInstanceAccessor) shader).afterburner$uniforms().size();
		if (nowhere.length < n) {
			nowhere = new int[n * 2];
			Arrays.fill(nowhere, -1);
		}
		return nowhere;
	}

	/**
	 * Points a game shader's Uniforms at the pack program now in use and uploads them there: what the game sets on them
	 * until the shader is cleared or applied again (the chunk offset of each region, ...) goes to the pack's.
	 */
	static void swapUniforms(ShaderInstance shader, int[] locations) {
		restoreUniforms();
		List<Uniform> uniforms = ((ShaderInstanceAccessor) shader).afterburner$uniforms();
		int n = Math.min(uniforms.size(), locations.length);
		if (savedLocations.length < n) savedLocations = new int[n * 2];
		for (int i = 0; i < n; i++) {
			Uniform u = uniforms.get(i);
			savedLocations[i] = u.getLocation();
			u.setLocation(locations[i]);
			u.upload();
		}
		swapped = n == uniforms.size() ? uniforms : uniforms.subList(0, n);
	}

	/** Gives the game's Uniforms their own locations back. */
	static void restoreUniforms() {
		List<Uniform> uniforms = swapped;
		if (uniforms == null) return;
		swapped = null;
		for (int i = 0; i < uniforms.size(); i++) uniforms.get(i).setLocation(savedLocations[i]);
	}
}
