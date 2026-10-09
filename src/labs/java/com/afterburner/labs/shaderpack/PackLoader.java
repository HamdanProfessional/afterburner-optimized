package com.afterburner.labs.shaderpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Loads a pack for one dimension, everything short of the GPU: finds its programs, preprocesses them, reads shaders.properties
 * and the custom uniforms, and lays out the uniform blocks all programs share. Programs that fail are left out with a warning,
 * so one broken program doesn't take the pack down.
 */
public final class PackLoader {
	/** The most color attachments a pass may have: what GPUs have (OpenGL's GL_MAX_DRAW_BUFFERS is at least 8). */
	public static final int MAX_ATTACHMENTS = 8;
	/** The programs each of the game's world passes draws with, as game.ProgramMapping picks them: the sky, the main pass, the hand. */
	private static final List<String> SKY_PROGRAMS = List.of("gbuffers_skybasic", "gbuffers_skytextured");
	private static final List<String> MAIN_PROGRAMS = List.of("gbuffers_terrain_solid", "gbuffers_terrain_cutout", "dh_terrain",
		"gbuffers_water", "dh_water", "gbuffers_block", "gbuffers_block_translucent", "gbuffers_damagedblock", "gbuffers_spidereyes",
		"gbuffers_lightning", "gbuffers_beaconbeam", "gbuffers_textured_lit", "gbuffers_particles", "gbuffers_particles_translucent",
		"gbuffers_weather", "gbuffers_basic", "gbuffers_line", "gbuffers_armor_glint", "gbuffers_entities", "gbuffers_entities_translucent");
	private static final List<String> HAND_PROGRAMS = List.of("gbuffers_hand", "gbuffers_hand_water", "gbuffers_armor_glint");
	/** In the main pass, the terrain is drawn only before the deferred passes, and the water only after them. */
	private static final Set<String> BEFORE_DEFERRED = Set.of("gbuffers_terrain_solid", "gbuffers_terrain_cutout", "dh_terrain");
	private static final Set<String> AFTER_DEFERRED = Set.of("gbuffers_water", "dh_water");

	public record Program(String name, TranslateTarget.Kind kind, PackPrograms.Source source, GlslPreprocessor.Result vsh,
			GlslPreprocessor.Result fsh, GlslTranslator.Parsed vs, GlslTranslator.Parsed fs, ProgramSettings directives) {
		/** The buffers the program draws to: its directive, or one per output. */
		public int[] drawBuffers() {
			if (this.directives.drawBuffers != null) return this.directives.drawBuffers;
			int[] out = new int[this.fs.outputCount()];
			for (int i = 0; i < out.length; i++) out[i] = i;
			return out;
		}
	}

	/** A compute program (a .csh file) of pass {@code pass} ("shadowcomp", "composite1", ...). */
	public record ComputeProgram(String pass, String path, GlslPreprocessor.Result source, GlslTranslator.Parsed cs,
			ProgramSettings directives) {}

	public static final class Loaded {
		public final String folder;
		public final PackPrograms programSet;
		public final Map<String, Program> programs;
		public final UniformLayout layout;
		public final PackProperties properties;
		public final PackUniforms customUniforms;
		/** The const settings of all programs (colortex0Format, shadowMapResolution, ...), later programs winning. */
		public final Map<String, String> consts;
		/** The buffers the gbuffers programs draw to, together: the color attachments of the world passes. */
		public final int[] worldBuffers;
		/**
		 * The color attachments of each world pass: the sky's, the main pass's before the deferred passes and after them
		 * (translucents), the hand's. All are worldBuffers, unless those are more than a pass may have ({@link #splitsWorld}):
		 * then each pass has the buffers of the programs it draws with.
		 */
		public final int[] skyBuffers, opaqueBuffers, translucentBuffers, handBuffers;
		/** block.properties: the block IDs terrain gets in mc_Entity. */
		public final BlockIdRules blockIds;
		/** Pass name to its compute programs, in the order they run ({@link PackPrograms#COMPUTE_GROUPS}). */
		public final Map<String, List<ComputeProgram>> computes;
		/** Custom images and storage buffers. */
		public final PackImages images;
		/** The storage buffer blocks any program declares: block name to its binding (the bufferObject index). */
		public final Map<String, Integer> bufferBlocks;
		/** The blend.* directives. */
		public final PackBlending blending;
		/** The alphaTest.* directives: program to its test. */
		public final Map<String, AlphaCutoff> alphaTests;
		public final List<String> warnings;

		Loaded(String folder, PackPrograms programSet, Map<String, Program> programs, UniformLayout layout, PackProperties properties,
				PackUniforms customUniforms, Map<String, String> consts, int[] worldBuffers, BlockIdRules blockIds,
				Map<String, List<ComputeProgram>> computes, PackImages images, Map<String, Integer> bufferBlocks, List<String> warnings) {
			this.folder = folder;
			this.programSet = programSet;
			this.programs = programs;
			this.layout = layout;
			this.properties = properties;
			this.customUniforms = customUniforms;
			this.consts = consts;
			this.worldBuffers = worldBuffers;
			this.blockIds = blockIds;
			this.computes = computes;
			this.images = images;
			this.bufferBlocks = bufferBlocks;
			this.warnings = warnings;
			this.blending = PackBlending.parse(properties, warnings);
			this.alphaTests = AlphaCutoff.directives(properties, warnings);
			this.skyBuffers = this.passBuffers(SKY_PROGRAMS, Set.of());
			this.opaqueBuffers = this.passBuffers(MAIN_PROGRAMS, AFTER_DEFERRED);
			this.translucentBuffers = this.passBuffers(MAIN_PROGRAMS, BEFORE_DEFERRED);
			this.handBuffers = this.passBuffers(HAND_PROGRAMS, Set.of());
		}

		/** Whether the world passes have buffers of their own, worldBuffers being more than {@link #MAX_ATTACHMENTS} (Astralex). */
		public boolean splitsWorld() {
			return this.worldBuffers.length > MAX_ATTACHMENTS;
		}

		/** The color attachments a program is drawn with: those of the first world pass that draws with it (for checking packs). */
		public int[] buffersOf(String program) {
			if (!this.splitsWorld()) return this.worldBuffers;
			if (this.drawsWith(MAIN_PROGRAMS, AFTER_DEFERRED, program)) return this.opaqueBuffers;
			if (this.drawsWith(MAIN_PROGRAMS, BEFORE_DEFERRED, program)) return this.translucentBuffers;
			if (this.drawsWith(SKY_PROGRAMS, Set.of(), program)) return this.skyBuffers;
			if (this.drawsWith(HAND_PROGRAMS, Set.of(), program)) return this.handBuffers;
			return this.worldBuffers;
		}

		private boolean drawsWith(List<String> slots, Set<String> leftOut, String program) {
			for (String slot : slots) {
				Program p = leftOut.contains(slot) ? null : this.get(slot);
				if (p != null && p.name().equals(program)) return true;
			}
			return false;
		}

		/** The buffers the programs of {@code slots} (after fallbacks) draw to, but those of {@code leftOut}. */
		private int[] passBuffers(List<String> slots, Set<String> leftOut) {
			if (!this.splitsWorld()) return this.worldBuffers;
			TreeSet<Integer> buffers = new TreeSet<>();
			for (String slot : slots) {
				Program p = leftOut.contains(slot) ? null : this.get(slot);
				if (p != null) for (int b : p.drawBuffers()) buffers.add(b);
			}
			// A pass needs somewhere to draw, even where the pack draws nothing.
			if (buffers.isEmpty()) buffers.add(this.worldBuffers[0]);
			return buffers.stream().mapToInt(Integer::intValue).toArray();
		}

		/** The program for a slot after fallbacks (gbuffers_water may be gbuffers_terrain), or null. */
		public @Nullable Program get(String name) {
			PackPrograms.Source source = this.programSet.get(name);
			return source == null ? null : this.programs.get(source.name());
		}

		public GlslTranslator.Program translate(Program program, TranslateTarget target) throws GlslTranslator.TranslateException {
			return GlslTranslator.translate(program.vs, program.fs, program.directives.drawBuffers, target, this.layout);
		}

		public GlslTranslator.Compute translate(ComputeProgram program) throws GlslTranslator.TranslateException {
			return GlslTranslator.translateCompute(program.cs, this.layout);
		}
	}

	private PackLoader() {
	}

	/**
	 * {@code dimensionId} is like "minecraft:overworld"; {@code macros} are {@link PackMacros}; {@code constants} are the
	 * names custom uniforms may use as numbers (BIOME_PLAINS, ...).
	 */
	public static Loaded load(PackFiles pack, String dimensionId, Map<String, String> macros, Map<String, String> constants) throws Exception {
		List<String> warnings = new ArrayList<>();
		PackProperties dimensions = null;
		if (pack.exists("/dimension.properties")) dimensions = properties(pack, "/dimension.properties", macros, Map.of());
		String folder = PackPrograms.dimensionFolder(pack, dimensions, dimensionId);

		// Every program first, for the options' macros (shaders.properties is preprocessed with them).
		PackPrograms everything = PackPrograms.load(pack, folder, key -> true);
		Map<String, GlslPreprocessor.Result[]> sources = new LinkedHashMap<>();
		Map<String, GlslPreprocessor.Macro> optionMacros = new LinkedHashMap<>();
		for (PackPrograms.Source source : everything.all().values()) {
			try {
				GlslPreprocessor.Result vsh = preprocess(pack, source.vertex(), macros);
				GlslPreprocessor.Result fsh = preprocess(pack, source.fragment(), macros);
				sources.put(source.name(), new GlslPreprocessor.Result[] {vsh, fsh});
				optionMacros.putAll(vsh.macros);
				optionMacros.putAll(fsh.macros);
				for (String w : vsh.warnings) warnings.add(source.vertex() + ": " + w);
				for (String w : fsh.warnings) warnings.add(source.fragment() + ": " + w);
			} catch (GlslPreprocessor.PreprocessException e) {
				warnings.add(source.name() + ": " + e.getMessage());
			}
		}

		GlslPreprocessor propertiesPp = new GlslPreprocessor(pack::read, GlslPreprocessor.Mode.PROPERTIES);
		propertiesPp.defineAll(optionMacros);
		PackMacros.apply(propertiesPp, macros);
		PackProperties properties = pack.exists("/shaders.properties")
			? PackProperties.parse(propertiesPp.process("/shaders.properties"))
			: PackProperties.parse(propertiesPp.processText("/shaders.properties", ""));
		boolean farTerrain = macros.containsKey("DISTANT_HORIZONS");
		PackPrograms programSet = PackPrograms.load(pack, folder, key -> {
			// Distant Horizons' programs need its macros: without them the far terrain isn't drawn with them.
			if (!farTerrain && key.matches("program\\.(.*/)?dh_.*")) return false;
			String value = properties.get(key);
			if (value == null) return true;
			try {
				return propertiesPp.evaluate(optionExpression(value, optionMacros));
			} catch (GlslPreprocessor.PreprocessException e) {
				warnings.add(key + ": " + e.getMessage());
				return true;
			}
		});

		Map<String, Program> programs = new LinkedHashMap<>();
		UniformLayout layout = new UniformLayout();
		Map<String, String> consts = new LinkedHashMap<>();
		for (PackPrograms.Source source : programSet.all().values()) {
			GlslPreprocessor.Result[] pp = sources.get(source.name());
			if (pp == null) continue;
			try {
				GlslTranslator.Parsed vs = GlslTranslator.parse(pp[0], GlslTranslator.Stage.VERTEX);
				GlslTranslator.Parsed fs = GlslTranslator.parse(pp[1], GlslTranslator.Stage.FRAGMENT);
				GlslTranslator.collectUniforms(vs, layout);
				GlslTranslator.collectUniforms(fs, layout);
				ProgramSettings directives = ProgramSettings.of(pp[1]);
				consts.putAll(ProgramSettings.of(pp[0]).consts);
				consts.putAll(directives.consts);
				programs.put(source.name(), new Program(source.name(), kind(source.name()), source, pp[0], pp[1], vs, fs, directives));
			} catch (GlslTranslator.TranslateException e) {
				warnings.add(source.name() + ": " + e.getMessage());
			}
		}
		Map<String, List<ComputeProgram>> computes = new LinkedHashMap<>();
		for (Map.Entry<String, List<String>> e : programSet.computes().entrySet()) {
			List<ComputeProgram> list = new ArrayList<>();
			for (String path : e.getValue()) {
				try {
					GlslPreprocessor.Result source = preprocess(pack, path, macros);
					for (String w : source.warnings) warnings.add(path + ": " + w);
					GlslTranslator.Parsed cs = GlslTranslator.parse(source, GlslTranslator.Stage.COMPUTE);
					GlslTranslator.collectUniforms(cs, layout);
					list.add(new ComputeProgram(e.getKey(), path, source, cs, ProgramSettings.of(source)));
				} catch (GlslPreprocessor.PreprocessException | GlslTranslator.TranslateException ex) {
					warnings.add(path + ": " + ex.getMessage());
				}
			}
			if (!list.isEmpty()) computes.put(e.getKey(), list);
		}
		if (programs.values().stream().anyMatch(p -> p.kind() == TranslateTarget.Kind.SHADOW)) {
			layout.add(TranslateTarget.SHADOW_FROM_VIEW, "mat4", 0);
			layout.add(TranslateTarget.SHADOW_PROJECTION, "mat4", 0);
		}
		layout.finish();

		Map<String, Integer> bufferBlocks = new LinkedHashMap<>();
		for (Program p : programs.values()) {
			bufferBlocks.putAll(p.vs.buffers);
			bufferBlocks.putAll(p.fs.buffers);
		}
		for (List<ComputeProgram> list : computes.values()) for (ComputeProgram c : list) bufferBlocks.putAll(c.cs.buffers);
		PackImages images = PackImages.parse(properties, optionMacros);
		warnings.addAll(images.warnings);
		for (Map.Entry<String, Integer> e : bufferBlocks.entrySet()) {
			if (e.getValue() < 0) warnings.add("Storage buffer " + e.getKey() + " has no binding; it won't get a bufferObject");
			else if (!images.buffers.containsKey(e.getValue())) warnings.add("Storage buffer " + e.getKey() + " uses bufferObject." + e.getValue() + ", which shaders.properties doesn't set");
		}

		TreeSet<Integer> world = new TreeSet<>();
		for (Program p : programs.values()) {
			if (p.kind != TranslateTarget.Kind.GBUFFERS) continue;
			for (int b : p.drawBuffers()) world.add(b);
		}
		int[] worldBuffers = world.stream().mapToInt(Integer::intValue).toArray();

		PackUniforms custom = PackUniforms.parse(properties, constants);
		warnings.addAll(custom.warnings());
		BlockIdRules blockIds = BlockIdRules.NONE;
		if (pack.exists("/block.properties")) {
			try {
				blockIds = BlockIdRules.parse(properties(pack, "/block.properties", macros, optionMacros));
				warnings.addAll(blockIds.warnings);
			} catch (GlslPreprocessor.PreprocessException e) {
				warnings.add("block.properties: " + e.getMessage());
			}
		}
		return new Loaded(folder, programSet, programs, layout, properties, custom, consts, worldBuffers, blockIds, computes, images,
			bufferBlocks, warnings);
	}

	/**
	 * An expression over options as an #if expression: a switch option (a macro without a value, like
	 * {@code #define SHADOW_CASTING}) means whether it's defined.
	 */
	static String optionExpression(String expression, Map<String, GlslPreprocessor.Macro> macros) {
		StringBuilder out = new StringBuilder();
		boolean afterDefined = false;
		for (GlslPreprocessor.Token t : GlslPreprocessor.tokenize(expression)) {
			if (t.kind() == GlslPreprocessor.Kind.IDENT && !afterDefined && !t.text().equals("defined")) {
				GlslPreprocessor.Macro macro = macros.get(t.text());
				if (macro == null || macro.body().isEmpty()) {
					out.append("defined(").append(t.text()).append(')');
					continue;
				}
			}
			if (t.kind() != GlslPreprocessor.Kind.SPACE) afterDefined = t.text().equals("defined") || (afterDefined && t.text().equals("("));
			out.append(t.text());
		}
		return out.toString();
	}

	public static TranslateTarget.Kind kind(String program) {
		if (program.startsWith("gbuffers_") || program.startsWith("dh_")) return TranslateTarget.Kind.GBUFFERS;
		if (program.startsWith("shadow") && !program.startsWith("shadowcomp")) return TranslateTarget.Kind.SHADOW;
		return TranslateTarget.Kind.FULLSCREEN;
	}

	private static GlslPreprocessor.Result preprocess(PackFiles pack, String path, Map<String, String> macros)
			throws GlslPreprocessor.PreprocessException {
		GlslPreprocessor pp = new GlslPreprocessor(pack::read, GlslPreprocessor.Mode.GLSL);
		PackMacros.apply(pp, macros);
		return pp.process(path);
	}

	private static PackProperties properties(PackFiles pack, String path, Map<String, String> macros,
			Map<String, GlslPreprocessor.Macro> options) throws GlslPreprocessor.PreprocessException {
		GlslPreprocessor pp = new GlslPreprocessor(pack::read, GlslPreprocessor.Mode.PROPERTIES);
		pp.defineAll(options);
		PackMacros.apply(pp, macros);
		return PackProperties.parse(pp.process(path));
	}
}
