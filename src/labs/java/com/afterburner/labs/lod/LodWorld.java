package com.afterburner.labs.lod;

import com.afterburner.labs.worldgen.FarLand;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The far terrain of one world's dimension: its sheets ({@link LodSheet}) at every level, kept in region files of 32 x 32
 * sheets under {@code afterburner/lod/<world>/<dimension>/} in the game folder, and the palettes their numbers point into.
 * Everything here is for the worker thread, except the node versions the renderer reads.
 */
final class LodWorld {
	private static final Logger LOGGER = LoggerFactory.getLogger("afterburner");
	static final int MAX_LEVEL = 4;
	private static final int REGION = 32, MAGIC = 0x41424C44, FORMAT = 1, CACHED_SHEETS = 3072;

	final Path folder;
	/** The dimension's lowest block and height, in blocks. */
	final int minY, height;
	final boolean skyLight;
	private final Registry<Biome> biomeRegistry;

	private final List<BlockState> states = new ArrayList<>();
	private final Reference2IntOpenHashMap<BlockState> stateIds = new Reference2IntOpenHashMap<>();
	private byte[] kinds = new byte[64], materials = new byte[64];
	/** {@link #cell}'s answers for the last states seen, by the state's identity hash. */
	private final @Nullable BlockState[] recentStates = new BlockState[256];
	private final int[] recentCells = new int[256];
	private int savedStates;
	private final List<Identifier> biomeNames = new ArrayList<>();
	private final Object2IntOpenHashMap<Identifier> biomeIds = new Object2IntOpenHashMap<>();
	private @Nullable Biome[] biomes = new Biome[16];
	private int savedBiomes;

	private final Long2ObjectOpenHashMap<Region> regions = new Long2ObjectOpenHashMap<>();
	private final LinkedHashMap<Long, LodSheet> cache = new LinkedHashMap<>(256, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, LodSheet> eldest) {
			return size() > CACHED_SHEETS;
		}
	};
	/** Per node ({@link #key}): bumped when one of its sheets, or a neighbor's sheet along its edge, changed. */
	final ConcurrentHashMap<Long, Integer> versions = new ConcurrentHashMap<>();
	/** Makes the land never seen, in single player; set once, right after this is made. */
	volatile @Nullable LodGenerator generator;
	/** Set by the worker once it's saved this for the last time: land made after that is left out. */
	volatile boolean closed;
	/** Added to every node's version: bumped to have every node built again (when Unseen Land is turned on). */
	volatile int epoch;

	LodWorld(Path folder, int minY, int height, boolean skyLight, Registry<Biome> biomeRegistry) {
		this.folder = folder;
		this.minY = minY;
		this.height = height;
		this.skyLight = skyLight;
		this.biomeRegistry = biomeRegistry;
		states.add(Blocks.AIR.defaultBlockState());
		// The hidden placeholder is colored as stone; stone itself gets a place of its own.
		states.add(Blocks.STONE.defaultBlockState());
		stateIds.defaultReturnValue(-1);
		biomeIds.defaultReturnValue(-1);
		readPalettes();
	}

	static long key(int level, int x, int z) {
		return (long) level << 58 | (x & 0x1FFFFFFFL) << 29 | (z & 0x1FFFFFFFL);
	}

	/** The dimension's height in voxels at a level. */
	int heightAt(int level) {
		return (height + (1 << level) - 1) >> level;
	}

	// ---- Palettes ----

	int stateId(BlockState state) {
		int id = stateIds.getInt(state);
		if (id < 0) {
			id = states.size();
			states.add(state);
			stateIds.put(state, id);
		}
		return id;
	}

	/**
	 * A block's kind ({@link LodKinds}) in the low 8 bits over its sheet cell: {@code stateId << 8}, 0 for an empty one.
	 * Remembers the last states seen: the game's own state ids are a big hash map, slow to look in voxel by voxel.
	 */
	int cell(BlockState state) {
		int h = System.identityHashCode(state) & 255;
		if (recentStates[h] != state) {
			byte kind = LodKinds.of(state);
			recentCells[h] = kind == LodKinds.EMPTY ? 0 : stateId(state) << 8 | kind;
			recentStates[h] = state;
		}
		return recentCells[h];
	}

	BlockState state(int id) {
		return id < states.size() ? states.get(id) : states.get(LodSheet.HIDDEN);
	}

	byte kind(int id) {
		if (id == LodSheet.AIR) return LodKinds.EMPTY;
		if (id == LodSheet.HIDDEN || id >= states.size()) return LodKinds.SOLID;
		if (id >= kinds.length) kinds = Arrays.copyOf(kinds, Math.max(id + 1, kinds.length * 2));
		byte k = kinds[id];
		if (k == 0) kinds[id] = k = (byte) (LodKinds.of(states.get(id)) + 1);
		return (byte) (k - 1);
	}

	/** A block's material slot for shader packs ({@link LodKinds#material}). */
	int material(int id) {
		if (id == LodSheet.AIR || id == LodSheet.HIDDEN || id >= states.size()) return LodKinds.UNKNOWN_SLOT;
		if (id >= materials.length) materials = Arrays.copyOf(materials, Math.max(id + 1, materials.length * 2));
		byte m = materials[id];
		if (m == 0) materials[id] = m = (byte) (LodKinds.material(states.get(id), kind(id)) + 1);
		return m - 1;
	}

	int biomeId(Identifier name) {
		int id = biomeIds.getInt(name);
		if (id < 0) {
			id = biomeNames.size();
			biomeNames.add(name);
			biomeIds.put(name, id);
		}
		return id;
	}

	/** The biome ({@link LodSheet#biomes}); plains for one this game doesn't know (a removed mod's), null if not even that. */
	@Nullable Biome biome(int id) {
		id &= LodSheet.BIOME;
		if (id >= biomeNames.size()) return null;
		if (id >= biomes.length) biomes = Arrays.copyOf(biomes, Math.max(id + 1, biomes.length * 2));
		Biome b = biomes[id];
		if (b == null) {
			b = biomeRegistry.getValue(biomeNames.get(id));
			if (b == null) b = biomeRegistry.getValue(Identifier.withDefaultNamespace("plains"));
			biomes[id] = b;
		}
		return b;
	}

	private void readPalettes() {
		try {
			Path file = folder.resolve("palette.txt");
			if (Files.exists(file)) {
				List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
				for (int i = 2; i < lines.size(); i++) {
					BlockState state;
					try {
						state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, lines.get(i), false).blockState();
					} catch (CommandSyntaxException e) {
						// A block this game doesn't have: drawn as the hidden placeholder.
						state = Blocks.STONE.defaultBlockState();
					}
					states.add(state);
					stateIds.putIfAbsent(state, states.size() - 1);
				}
			}
			savedStates = states.size();
			Path biomeFile = folder.resolve("biomes.txt");
			if (Files.exists(biomeFile)) {
				for (String line : Files.readAllLines(biomeFile, StandardCharsets.UTF_8)) {
					Identifier name = Identifier.tryParse(line.strip());
					if (name == null) name = Identifier.withDefaultNamespace("plains");
					biomeNames.add(name);
					biomeIds.putIfAbsent(name, biomeNames.size() - 1);
				}
			}
			savedBiomes = biomeNames.size();
		} catch (IOException e) {
			LOGGER.warn("[Afterburner] Couldn't read the far terrain palettes in {}: {}", folder, e.toString());
		}
	}

	// ---- Sheets ----

	@Nullable LodSheet get(int level, int sx, int sz) {
		long k = key(level, sx, sz);
		LodSheet sheet = cache.get(k);
		if (sheet != null) return sheet;
		Region region = region(level, sx >> 5, sz >> 5);
		region.lastUse = System.nanoTime();
		int index = (sz & 31) << 5 | (sx & 31);
		byte[] bytes = region.sheets[index];
		if (bytes == null) return null;
		sheet = LodSheet.decode(bytes);
		if (sheet == null) {
			region.sheets[index] = null;
			return null;
		}
		if (sheet.lightMissing()) {
			// Saved by an older build from a chunk copied without its light: let it go, it comes back when the chunk does.
			region.sheets[index] = null;
			region.dirty = true;
			return null;
		}
		// Made by an older build's far land: made again.
		if (sheet.dropMadeBefore(FarLand.VERSION) && sheet.isEmpty()) {
			region.sheets[index] = null;
			region.dirty = true;
			return null;
		}
		cache.put(k, sheet);
		return sheet;
	}

	/** Stores a sheet; false if it's what was there already. */
	boolean put(int level, int sx, int sz, LodSheet sheet) {
		Region region = region(level, sx >> 5, sz >> 5);
		region.lastUse = System.nanoTime();
		int index = (sz & 31) << 5 | (sx & 31);
		byte[] bytes = sheet.encode();
		cache.put(key(level, sx, sz), sheet);
		if (Arrays.equals(region.sheets[index], bytes)) return false;
		region.sheets[index] = bytes;
		region.dirty = true;
		bump(level, sx >> 3, sz >> 3);
		if ((sx & 7) == 0) bump(level, (sx >> 3) - 1, sz >> 3);
		if ((sx & 7) == 7) bump(level, (sx >> 3) + 1, sz >> 3);
		if ((sz & 7) == 0) bump(level, sx >> 3, (sz >> 3) - 1);
		if ((sz & 7) == 7) bump(level, sx >> 3, (sz >> 3) + 1);
		return true;
	}

	private void bump(int level, int nx, int nz) {
		versions.merge(key(level, nx, nz), 1, Integer::sum);
	}

	int version(int level, int nx, int nz) {
		return versions.getOrDefault(key(level, nx, nz), 0) + epoch;
	}

	private Region region(int level, int rx, int rz) {
		long k = key(level, rx, rz);
		Region region = regions.get(k);
		if (region == null) {
			region = new Region(level, rx, rz);
			read(region);
			regions.put(k, region);
		}
		return region;
	}

	private Path file(Region r) {
		return folder.resolve("L" + r.level).resolve("r." + r.rx + "." + r.rz + ".lod");
	}

	private void read(Region region) {
		Path file = file(region);
		if (!Files.exists(file)) return;
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(file)))) {
			if (in.readInt() != MAGIC || in.readInt() != FORMAT) return;
			while (true) {
				int index = in.readShort();
				if (index < 0 || index >= REGION * REGION) break;
				byte[] bytes = new byte[in.readInt()];
				in.readFully(bytes);
				region.sheets[index] = bytes;
			}
		} catch (IOException | NegativeArraySizeException e) {
			LOGGER.warn("[Afterburner] Damaged far terrain file {}: {}", file, e.toString());
		}
	}

	/** Writes what changed: the palettes first, so no sheet on disk ever points past them. */
	void save() {
		try {
			Files.createDirectories(folder);
			if (states.size() > savedStates || !Files.exists(folder.resolve("palette.txt"))) {
				List<String> lines = new ArrayList<>(states.size());
				lines.add("afterburner:air");
				lines.add("afterburner:hidden");
				for (int i = 2; i < states.size(); i++) lines.add(BlockStateParser.serialize(states.get(i)));
				writeAtomically(folder.resolve("palette.txt"), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
				savedStates = states.size();
			}
			if (biomeNames.size() > savedBiomes) {
				List<String> lines = new ArrayList<>(biomeNames.size());
				for (Identifier name : biomeNames) lines.add(name.toString());
				writeAtomically(folder.resolve("biomes.txt"), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
				savedBiomes = biomeNames.size();
			}
			for (Region region : regions.values()) {
				if (!region.dirty) continue;
				ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
				try (DataOutputStream out = new DataOutputStream(bytes)) {
					out.writeInt(MAGIC);
					out.writeInt(FORMAT);
					for (int i = 0; i < region.sheets.length; i++) {
						byte[] sheet = region.sheets[i];
						if (sheet == null) continue;
						out.writeShort(i);
						out.writeInt(sheet.length);
						out.write(sheet);
					}
					out.writeShort(-1);
				}
				Path file = file(region);
				Files.createDirectories(file.getParent());
				writeAtomically(file, bytes.toByteArray());
				region.dirty = false;
			}
		} catch (IOException e) {
			LOGGER.warn("[Afterburner] Couldn't save the far terrain in {}: {}", folder, e.toString());
		}
	}

	private static void writeAtomically(Path file, byte[] data) throws IOException {
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.write(tmp, data);
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Lets go of saved regions unused for a minute, so a long trip doesn't keep the whole map in memory. */
	void trim() {
		long now = System.nanoTime();
		for (Iterator<Region> it = regions.values().iterator(); it.hasNext(); ) {
			Region region = it.next();
			if (!region.dirty && now - region.lastUse > 60_000_000_000L) it.remove();
		}
	}

	private static final class Region {
		final int level, rx, rz;
		final byte[][] sheets = new byte[REGION * REGION][];
		boolean dirty;
		long lastUse = System.nanoTime();

		Region(int level, int rx, int rz) {
			this.level = level;
			this.rx = rx;
			this.rz = rz;
		}
	}
}
