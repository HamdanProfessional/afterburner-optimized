package com.afterburner;

import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Which optimizations are on. All are on by default; players turn them off in the settings screen or
 * {@code config/afterburner.properties} ({@link Settings}). For testing, a JVM flag such as
 * {@code -Dafterburner.parallelWorldgen=false} wins over the file, and {@code -Dafterburner.disable=true} turns all off.
 */
public enum Features {
	/** Leaves out of each chunk draw the block faces that point away from the camera. */
	FACE_CULLING("faceCulling", "face culling", Group.RENDER, "sodium"),
	/** Leaves out of water, glass and ice draws the flat faces that point away from the camera (the upside-down water top, for one). Needs chunk batching. */
	TRANSLUCENT_CULLING("translucentCulling", "translucent face culling", Group.RENDER, "sodium"),
	/** Leaves out the faces between leaf blocks deep inside trees, which see-through leaves otherwise all draw. */
	LEAF_CULLING("leafCulling", "leaf culling", Group.RENDER, "moreculling", "cullleaves", "cull-less-leaves"),
	/** Skips work vanilla does every frame for chunk layers that have nothing to draw. */
	DRAW_PREP("drawPrep", "faster draw prep", Group.RENDER, "sodium"),
	/** Draws the chunk sections of each 128 x 64 x 128 block region with one call instead of one call per section. */
	CHUNK_BATCHING("chunkBatching", "batched chunk drawing", Group.RENDER, "sodium"),
	/** Chunk vertices take 16 bytes instead of 28, so the graphics card has less to read for every block face. Needs chunk batching. */
	COMPACT_VERTICES("compactVertices", "smaller chunk vertices", Group.RENDER, "sodium"),
	/** Builds the chunk sections around the camera that are off screen while the builders have time, not only once looked at. */
	OFFSCREEN_BUILDS("offscreenBuilds", "off-screen chunk building", Group.RENDER, "sodium"),
	/** Skips drawing the chunk sections hidden behind nearer terrain (OpenGL 4.3 and up, or 3.3 with the same extensions). Needs chunk batching. */
	OCCLUSION_CULLING("occlusionCulling", "occlusion culling", Group.RENDER, "sodium", "iris"),
	/** Skips drawing the entities and block entities hidden behind terrain, using the occlusion culling test. Needs occlusion culling. */
	ENTITY_CULLING("entityCulling", "entity culling", Group.RENDER, "sodium", "iris", "entityculling"),
	/** Smooth texture filtering (RGSS) reads the texture 4 times per pixel instead of 8 or 9, for the same picture. */
	FAST_FILTERING("fastFiltering", "cheaper texture filtering", Group.RENDER),
	/** Animated block textures (water, lava, fire...) are only drawn into the texture atlas while something on screen uses them. Needs chunk batching. */
	VISIBLE_ANIMATIONS("visibleAnimations", "visible-only animations", Group.RENDER, "sodium"),
	/** The biome blend of water, grass and leaf colours looks each spot's biome up once per chunk section build instead of up to 25 times. Same colours. */
	FAST_BIOME_BLEND("fastBiomeBlend", "faster biome blending", Group.RENDER, "sodium"),
	/** While a chunk section is built, smooth lighting keeps the light of every block in and around it, not only the last 100 looked up. Same lighting. */
	SMOOTH_LIGHT_CACHE("smoothLightCache", "bigger smooth lighting cache", Group.RENDER, "sodium"),
	/** The visibility update remembers how far each section corner's line to the camera gets, instead of walking it again for every neighbour. Same sections in view. */
	FAST_VISIBILITY("fastVisibility", "faster visibility updates", Group.RENDER, "sodium"),
	/** The chunk renderer reserves GPU memory for meshes in pieces that grow with what's needed instead of 128 MB at a time. */
	CHUNK_BUFFERS("chunkBuffers", "smaller chunk buffers", Group.MEMORY, "sodium"),
	/** Built chunk sections let go of the copy of the blocks around them they were built from (vanilla keeps the last one). */
	BUILD_COPIES("buildCopies", "freed chunk build copies", Group.MEMORY, "sodium"),
	/** While the game is paused or in the background, Java gives the memory it isn't using back to the system. */
	MEMORY_TRIM("memoryTrim", "memory trim", Group.MEMORY),
	/** Places trees, ores and structures on every CPU core instead of one, keeping chunks that touch apart. */
	PARALLEL_WORLDGEN("parallelWorldgen", "parallel worldgen", Group.WORLD),
	/** Light updates remember the sections they work in instead of looking them up again for every block. */
	FAST_LIGHT("fastLight", "faster lighting", Group.WORLD, "scalablelux", "starlight", "moonrise", "phosphor"),
	/** Finds what moving entities bump into with less work per block, and skips entities that can't be bumped into (also for the checks for room items, XP orbs, arrows and mobs in water make each tick). */
	FAST_COLLISIONS("fastCollisions", "faster collisions", Group.WORLD, "lithium"),
	/** Entity searches (mobs pushing, items, hoppers, targeting) look up the few sections a box reaches instead of walking every one along x. */
	FAST_ENTITY_SEARCH("fastEntitySearch", "faster entity searches", Group.WORLD, "lithium", "moonrise"),
	/** Random block ticks pass by chunk sections with nothing in them that random-ticks without reading them, and the check whether a dimension has to keep running looks where it found its reason last tick first. */
	FAST_CHUNK_TICKS("fastChunkTicks", "faster chunk ticks", Group.WORLD, "lithium", "moonrise"),
	/** Mob brains walk their behaviors in plain arrays, and searches for job blocks, beds and the like skip the hash maps and streams. */
	FAST_MOB_AI("fastMobAi", "faster mob AI", Group.WORLD, "lithium"),
	/** Small things every mob does every tick with less work: the frost slowdown, rain putting out fire, its goals' turned-off kinds, how far it's sent to players when nothing rides it. */
	FAST_MOB_TICKS("fastMobTicks", "cheaper mob ticks", Group.WORLD, "lithium"),
	/** Hoppers looking for a minecart that holds items skip the entity sections that have none, instead of looking at every mob near them. */
	FAST_HOPPERS("fastHoppers", "faster hoppers", Group.WORLD, "lithium"),
	/** Natural spawning skips the biome lookup that only matters for fish, the second lookup of which mobs may spawn at a spot right after picking one there, and every mob's spawn cost lookup where no biome has costs. */
	FAST_SPAWNING("fastSpawning", "faster mob spawning", Group.WORLD, "lithium"),
	/** Villages, trial chambers, ancient cities and bastions check where their next piece fits against a list of boxes instead of a voxel shape that grows with every piece. */
	FAST_STRUCTURES("fastStructures", "faster structure layout", Group.WORLD, "structure_layout_optimizer"),
	/** Ore veins ask the world for its height once instead of twice for every block they place, and copper and iron veins share their richness noise instead of each working it out for the whole chunk. */
	FAST_ORES("fastOres", "faster ore placement", Group.WORLD),
	/** Which biome goes where in new land: vanilla's search tree read from flat arrays. */
	FAST_BIOME_SEARCH("fastBiomeSearch", "faster biome search", Group.WORLD, "c2me"),
	/** Chunks with block or fluid ticks due that can't run them (outside simulation distance) are set aside until they can, instead of being asked again every tick. */
	PARKED_TICKS("parkedTicks", "parked scheduled ticks", Group.WORLD, "lithium", "moonrise", "c2me"),
	/** Chunk sections check for use from two threads with one field instead of four objects per block and biome container. */
	LEAN_SECTIONS("leanSections", "leaner chunk sections", Group.WORLD, "ferritecore", "lithium", "moonrise"),
	/** The game's background workers run just below normal priority, so frames come first when every core is busy (client). */
	WORKER_PRIORITY("workerPriority", "lower worker priority", Group.OTHER);

	/** Which part of the settings screen and file an optimization is listed in, and where it does something. */
	public enum Group {
		RENDER("Rendering", "client"),
		MEMORY("Memory", "client"),
		WORLD("World and server", "server and client"),
		OTHER("Other", "client");

		public final String title;
		public final String side;

		Group(String title, String side) {
			this.title = title;
			this.side = side;
		}
	}

	private final String key;
	private final String label;
	private final Group group;
	/** Name of the installed mod that replaces this part of the game, if one is. */
	private final String replacedBy;
	/** Whether the game's code was changed for this, decided once at startup (below). */
	private boolean enabled;
	/** Whether it's on right now: only the {@link #live()} ones can differ from {@link #enabled}. */
	private volatile boolean active;

	/** @param replacedBy mods that replace the same part of the game; with one of them installed, this stays off */
	Features(String key, String label, Group group, String... replacedBy) {
		this.key = key;
		this.label = label;
		this.group = group;
		String name = null;
		for (String mod : replacedBy) {
			if (name == null) name = FabricLoader.getInstance().getModContainer(mod).map(c -> c.getMetadata().getName()).orElse(null);
		}
		this.replacedBy = name;
	}

	// After all the constants exist: the settings file lists them all when it's first written.
	static {
		boolean all = !Boolean.getBoolean("afterburner.disable");
		for (Features f : values()) {
			// A JVM flag (benchmarks, testing) wins over the settings file.
			String flag = System.getProperty("afterburner." + f.key);
			boolean on = all && f.replacedBy == null && (flag != null ? !"false".equalsIgnoreCase(flag) : Settings.get(f.key));
			f.enabled = on;
		}
		// In the order listed (a needed one comes first), so what it itself needs is settled first.
		for (Features f : values()) {
			if (f.needs() != null && !f.needs().enabled) f.enabled = false;
			f.active = f.enabled;
		}
	}

	/** The optimization this one is built on, which must be on too, or null. */
	public Features needs() {
		return switch (this) {
			case ENTITY_CULLING -> OCCLUSION_CULLING;
			case COMPACT_VERTICES, OCCLUSION_CULLING, TRANSLUCENT_CULLING, VISIBLE_ANIMATIONS -> CHUNK_BATCHING;
			default -> null;
		};
	}

	/** Set by a JVM flag, which the settings file can't change. */
	public boolean forced() {
		return Boolean.getBoolean("afterburner.disable") || System.getProperty("afterburner." + key) != null;
	}

	/** Whether this optimization's changes to the game are in. Fixed until the game restarts. */
	public boolean enabled() {
		return enabled;
	}

	/** Whether it's working right now: {@link #enabled()}, and not switched off while playing since. */
	public boolean active() {
		return active;
	}

	/** Can be switched off and on again while playing (the code it needs stays in either way). */
	public boolean live() {
		return this == OCCLUSION_CULLING || this == ENTITY_CULLING;
	}

	/** Switches a {@link #live()} one now; one that isn't enabled stays off until a restart. */
	public void setActive(boolean on) {
		if (live()) active = on && enabled;
	}

	public String key() {
		return key;
	}

	public String label() {
		return label;
	}

	public Group group() {
		return group;
	}

	/** The installed mod that does this part instead (it stays off then), or null. */
	public String replacedBy() {
		return replacedBy;
	}

	/** For benchmark reports, e.g. "parallel worldgen on, eager lighting off". */
	public static String summary() {
		List<String> parts = new ArrayList<>();
		for (Features f : values()) parts.add(f.label + (f.active ? " on" : " off"));
		return String.join(", ", parts);
	}
}
