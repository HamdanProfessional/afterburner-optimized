package com.afterburner.labs.shaderpack.game;

import com.afterburner.labs.shaderpack.AlphaTest;
import com.afterburner.labs.shaderpack.StandardMacros;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/** Which gbuffers program draws what each of the game's pipelines draws, and its render stage, by the pipeline's name. */
final class ProgramMapping {
	private ProgramMapping() {
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
			default:
				break;
		}
		if (name.contains("glint")) return "gbuffers_armor_glint";
		if (name.startsWith("lines") || name.startsWith("debug_") || name.equals("secondary_block_outline")) return "gbuffers_line";
		if (entityLike(name)) return translucent ? "gbuffers_entities_translucent" : "gbuffers_entities";
		return textured ? "gbuffers_textured_lit" : "gbuffers_basic";
	}

	/**
	 * The render stage of what a pipeline draws (the renderStage uniform, a number from {@link StandardMacros#RENDER_STAGES}).
	 * In the shadow pass it's the terrain's, so packs that fill voxels from terrain there find it.
	 */
	static int stage(String path, PackPass.Kind pass, boolean translucent) {
		String name = name(path);
		if (pass == PackPass.Kind.HAND) return stage(translucent ? "HAND_TRANSLUCENT" : "HAND_SOLID");
		if (pass == PackPass.Kind.SKY) {
			return stage(switch (name) {
				case "sunrise_sunset" -> "SUNSET";
				case "stars" -> "STARS";
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
			case "clouds", "flat_clouds" -> "CLOUDS";
			case "secondary_block_outline" -> "OUTLINE";
			default -> name.startsWith("lines") ? "OUTLINE"
				: name.startsWith("debug_") ? "DEBUG"
				: entityLike(name) || name.contains("glint") || name.equals("eyes") || name.equals("entity_shadow") ? "ENTITIES"
				: "NONE";
		});
	}

	/** A render stage's number, by its name in {@link StandardMacros#RENDER_STAGES}. */
	static int stage(String stage) {
		return Math.max(0, Arrays.asList(StandardMacros.RENDER_STAGES).indexOf(stage));
	}

	/** A pipeline's name without what Afterburner and the game add to it ("pipeline/compact_solid_terrain_extended"). */
	/**
	 * The alpha test for a pipeline whose program the pack gives none: where the game cuts out what it draws ({@code cutout}, the
	 * pipeline's ALPHA_CUTOUT, null for nowhere), as Iris, which keeps water and glass down to nearly no alpha in the gbuffers;
	 * and as Iris, a tenth for what the game's shaders cut out themselves (particles, rain, clouds, cracks, glowing eyes) and
	 * nearly nothing for text.
	 */
	static AlphaTest alphaTest(String path, PackPass.Kind pass, @Nullable String cutout) {
		String name = name(path);
		if (cutout != null) {
			if (!pass.shadow() && name.equals("translucent_terrain")) return AlphaTest.NON_ZERO;
			try {
				return new AlphaTest("GREATER", Float.parseFloat(cutout));
			} catch (NumberFormatException e) {
				return AlphaTest.ONE_TENTH;
			}
		}
		return switch (name) {
			case "opaque_particle", "translucent_particle", "weather", "clouds", "flat_clouds", "crumbling", "eyes" -> AlphaTest.ONE_TENTH;
			default -> name.startsWith("text") ? AlphaTest.NON_ZERO : AlphaTest.OFF;
		};
	}

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
