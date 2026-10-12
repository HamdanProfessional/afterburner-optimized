package com.afterburner.labs.shaderpack.game;

import com.afterburner.labs.shaderpack.AlphaCutoff;
import com.afterburner.labs.shaderpack.PackMacros;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Which gbuffers program draws what each of the game's shaders draws, and its render stage. A shader is named as the pipeline
 * that draws the same in later versions of the game ({@link #shaderPath}), from its own name and what the game is drawing.
 */
final class ProgramMapping {
	private ProgramMapping() {
	}

	/**
	 * A game shader's name ("rendertype_cutout_mipped", "afterburner_compact_solid", "particle") as the pipeline's that draws
	 * the same ("cutout_terrain"), by what's being drawn: {@code phase} is the part of the frame, {@code blend} whether it
	 * blends, {@code sky} what the sky draws.
	 */
	static String shaderPath(String shader, Shaderpacks.Phase phase, boolean blend, Shaderpacks.SkyStage sky) {
		String n = shader;
		int colon = n.indexOf(':');
		if (colon >= 0) n = n.substring(colon + 1);
		if (n.startsWith("rendertype_")) n = n.substring("rendertype_".length());
		if (n.startsWith("afterburner_compact_")) n = n.substring("afterburner_compact_".length());
		boolean blocks = phase == Shaderpacks.Phase.ENTITIES || phase == Shaderpacks.Phase.BLOCK_ENTITIES;
		switch (n) {
			case "solid":
				return blocks ? "solid_block" : "solid_terrain";
			case "cutout", "cutout_mipped":
				return blocks ? "cutout_block" : "cutout_terrain";
			case "translucent":
				return blocks ? "translucent_block" : "translucent_terrain";
			case "translucent_moving_block":
				return "translucent_block";
			case "tripwire":
				return "translucent_terrain";
			case "particle":
				return phase == Shaderpacks.Phase.WEATHER ? "weather" : blend ? "translucent_particle" : "opaque_particle";
			// The game's clouds are a mesh here (later versions build them in their shader): the pack's gbuffers_clouds draws it.
			case "clouds":
				return "vanilla_clouds";
			default:
				break;
		}
		if (phase == Shaderpacks.Phase.SKY) {
			switch (n) {
				case "position":
					return sky == Shaderpacks.SkyStage.STARS ? "stars" : "sky";
				case "position_color":
					return "sunrise_sunset";
				case "position_tex":
					return "celestial";
				case "position_tex_color":
					return "end_sky";
				default:
					break;
			}
		}
		if (phase == Shaderpacks.Phase.WEATHER && n.equals("position_tex")) return "world_border";
		// Block entities are drawn with the entities' shaders (chests, signs, banners): the pack's block program draws them.
		if (phase == Shaderpacks.Phase.BLOCK_ENTITIES && entityLike(n) && !n.contains("glint") && !n.startsWith("text")) {
			return blend ? "translucent_block" : "solid_block";
		}
		return n;
	}

	/**
	 * The program for a pipeline ("pipeline/solid_terrain"), or null when the pack draws nothing for it. {@code translucent} is
	 * whether the pipeline blends; {@code textured} whether its vertices have texture coordinates.
	 */
	static @Nullable String program(String path, PackPass.Kind pass, boolean translucent, boolean textured) {
		String name = name(path);
		if (pass.shadow()) {
			// The terrain, and what's drawn into the map with the entities (ShadowMap#renderEntities): mobs, block entities and
			// items cast shadows; text, particles, lines and the rest don't.
			if (name.equals("solid_terrain")) return "shadow_solid";
			if (name.equals("cutout_terrain")) return "shadow_cutout";
			if (name.equals("translucent_terrain")) return "shadow_water";
			if (name.endsWith("terrain") || name.equals("solid_block") || name.equals("cutout_block")) return "shadow";
			return entityLike(name) && !name.startsWith("text") && !name.contains("glint") && !name.equals("entity_shadow") ? "shadow_entities"
				: null;
		}
		if (pass == PackPass.Kind.HAND) {
			if (name.contains("glint")) return "gbuffers_armor_glint";
			return translucent ? "gbuffers_hand_water" : "gbuffers_hand";
		}
		if (pass == PackPass.Kind.SKY) {
			return switch (name) {
				case "celestial", "end_sky" -> "gbuffers_skytextured";
				default -> "gbuffers_skybasic";
			};
		}

		switch (name) {
			case "solid_terrain":
				return "gbuffers_terrain_solid";
			// Afterburner's far terrain, as Distant Horizons' (drawn in its own pass, see PackRenderer#drawFarTerrain).
			case "far_terrain":
				return "dh_terrain";
			// Its water, see-through (PackRenderer#drawFarWater).
			case "far_water":
				return "dh_water";
			case "cutout_terrain":
				return "gbuffers_terrain_cutout";
			case "translucent_terrain":
				return "gbuffers_water";
			case "solid_block", "cutout_block", "end_portal", "end_gateway":
				return "gbuffers_block";
			case "translucent_block":
				return "gbuffers_block_translucent";
			case "crumbling":
				return "gbuffers_damagedblock";
			case "eyes":
				return "gbuffers_spidereyes";
			case "lightning", "dragon_rays":
				return "gbuffers_lightning";
			case "beacon_beam", "beacon_beam_opaque", "beacon_beam_translucent":
				return "gbuffers_beaconbeam";
			case "entity_shadow", "world_border":
				return "gbuffers_textured_lit";
			case "opaque_particle", "particle":
				return "gbuffers_particles";
			case "translucent_particle":
				return "gbuffers_particles_translucent";
			case "weather":
				return "gbuffers_weather";
			case "leash", "water_mask", "oit_water_mask", "wireframe":
				return "gbuffers_basic";
			// The game's clouds are built in the vertex shader from a texture; packs draw their own.
			case "clouds", "flat_clouds":
				return null;
			case "vanilla_clouds":
				return "gbuffers_clouds";
			default:
				break;
		}
		if (name.contains("glint")) return "gbuffers_armor_glint";
		if (name.startsWith("lines") || name.startsWith("debug_") || name.equals("secondary_block_outline")) return "gbuffers_line";
		if (entityLike(name)) return translucent ? "gbuffers_entities_translucent" : "gbuffers_entities";
		return textured ? "gbuffers_textured_lit" : "gbuffers_basic";
	}

	/**
	 * The render stage of what a pipeline draws (the renderStage uniform, a number from {@link PackMacros#RENDER_STAGES}).
	 * In the shadow pass it's the terrain's, so packs that fill voxels from terrain there find it.
	 */
	static int stage(String path, PackPass.Kind pass, boolean translucent) {
		String name = name(path);
		if (pass == PackPass.Kind.HAND) return stage(translucent ? "HAND_TRANSLUCENT" : "HAND_SOLID");
		if (pass == PackPass.Kind.SKY) {
			return stage(switch (name) {
				case "sunrise_sunset" -> "SUNSET";
				case "stars" -> "STARS";
				case "void" -> "VOID";
				case "celestial", "end_sky" -> "CUSTOM_SKY";
				default -> "SKY";
			});
		}
		return stage(switch (name) {
			case "solid_terrain", "far_terrain" -> "TERRAIN_SOLID";
			case "cutout_terrain" -> "TERRAIN_CUTOUT";
			case "translucent_terrain", "far_water" -> "TERRAIN_TRANSLUCENT";
			case "solid_block", "cutout_block", "translucent_block", "end_portal", "end_gateway" -> "BLOCK_ENTITIES";
			case "crumbling" -> "DESTROY";
			case "opaque_particle", "particle", "translucent_particle" -> "PARTICLES";
			case "weather" -> "RAIN_SNOW";
			case "world_border" -> "WORLD_BORDER";
			case "clouds", "flat_clouds", "vanilla_clouds" -> "CLOUDS";
			case "secondary_block_outline" -> "OUTLINE";
			default -> name.startsWith("lines") ? "OUTLINE"
				: name.startsWith("debug_") ? "DEBUG"
				: entityLike(name) || name.contains("glint") || name.equals("eyes") || name.equals("entity_shadow") ? "ENTITIES"
				: "NONE";
		});
	}

	/** A render stage's number, by its name in {@link PackMacros#RENDER_STAGES}. */
	static int stage(String stage) {
		return Math.max(0, Arrays.asList(PackMacros.RENDER_STAGES).indexOf(stage));
	}

	/**
	 * The alpha test for a pipeline whose program the pack gives none: where the game cuts out what it draws ({@code cutout}, the
	 * pipeline's ALPHA_CUTOUT, null for nowhere), as Iris, which keeps water and glass down to nearly no alpha in the gbuffers;
	 * and as Iris, a tenth for what the game's shaders cut out themselves (particles, rain, clouds, cracks, glowing eyes) and
	 * nearly nothing for text.
	 */
	static AlphaCutoff alphaTest(String path, PackPass.Kind pass, @Nullable String cutout) {
		String name = name(path);
		if (cutout != null) {
			if (!pass.shadow() && name.equals("translucent_terrain")) return AlphaCutoff.NON_ZERO;
			try {
				return new AlphaCutoff("GREATER", Float.parseFloat(cutout));
			} catch (NumberFormatException e) {
				return AlphaCutoff.ONE_TENTH;
			}
		}
		return switch (name) {
			case "opaque_particle", "translucent_particle", "weather", "clouds", "flat_clouds", "vanilla_clouds", "crumbling", "eyes" -> AlphaCutoff.ONE_TENTH;
			default -> name.startsWith("text") ? AlphaCutoff.NON_ZERO : AlphaCutoff.OFF;
		};
	}

	/** A pipeline's name without what Afterburner and the game add to it ("pipeline/compact_solid_terrain_extended"). */
	private static String name(String path) {
		String name = path.startsWith("pipeline/") ? path.substring("pipeline/".length()) : path;
		if (name.endsWith("_multidraw")) name = name.substring(0, name.length() - "_multidraw".length());
		// Afterburner's 16-byte terrain: "compact_solid_terrain"; and its terrain built for packs: "compact_solid_terrain_extended".
		if (name.startsWith("compact_")) name = name.substring("compact_".length());
		if (name.endsWith("_extended")) name = name.substring(0, name.length() - "_extended".length());
		return name;
	}

	private static boolean entityLike(String name) {
		return name.startsWith("entity") || name.startsWith("armor_") || name.startsWith("item") || name.startsWith("text")
			|| name.equals("banner_pattern") || name.equals("breeze_wind") || name.equals("energy_swirl") || name.equals("end_crystal_beam");
	}
}
