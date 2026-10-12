package com.afterburner.ai;

import com.afterburner.mixin.ai.PoiSectionAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Vanilla's {@code PoiManager.getInSquare}: the points of interest within a square, in the same order (chunk columns x
 * first, then z; sections bottom to top; then the order each section keeps them in) and found as lazily, so a search
 * that stops early loads exactly what vanilla would. Vanilla does this with streams inside streams and looks every
 * section up in a hash map; this walks them directly and looks each column up once.
 */
public final class PoiSquare extends Spliterators.AbstractSpliterator<PoiRecord> {
	private final PoiColumns storage;
	private final Predicate<Holder<PoiType>> types;
	private final Predicate<? super PoiRecord> occupancy;
	private final int centerX, centerZ, radius;
	private final int minChunkX, minChunkZ, maxChunkX, maxChunkZ;
	private final int minSection, sectionCount;

	private boolean started;
	private int chunkX, chunkZ;
	/** The next section of the current column, as an index from its lowest one. */
	private int section;
	private Optional<?> @Nullable [] column;
	private @Nullable Iterator<Map.Entry<Holder<PoiType>, Set<PoiRecord>>> typeIterator;
	private @Nullable Iterator<PoiRecord> recordIterator;

	/** Only for {@code radius >= 0}; vanilla's square for a negative one is odd enough to leave to vanilla. */
	public PoiSquare(PoiColumns storage, Predicate<Holder<PoiType>> types, BlockPos center, int radius, Predicate<? super PoiRecord> occupancy) {
		// Like vanilla's ChunkPos.rangeClosed stream after flatMap and filter: size unknown, no characteristics.
		super(Long.MAX_VALUE, 0);
		this.storage = storage;
		this.types = types;
		this.occupancy = occupancy;
		this.centerX = center.getX();
		this.centerZ = center.getZ();
		this.radius = radius;
		int chunkRadius = Math.floorDiv(radius, 16) + 1;
		int chunkX = SectionPos.blockToSectionCoord(centerX), chunkZ = SectionPos.blockToSectionCoord(centerZ);
		this.minChunkX = chunkX - chunkRadius;
		this.minChunkZ = chunkZ - chunkRadius;
		this.maxChunkX = chunkX + chunkRadius;
		this.maxChunkZ = chunkZ + chunkRadius;
		this.minSection = storage.afterburner$minSection();
		this.sectionCount = storage.afterburner$sectionCount();
		this.section = sectionCount;
	}

	@Override
	public boolean tryAdvance(Consumer<? super PoiRecord> action) {
		while (true) {
			Iterator<PoiRecord> records = recordIterator;
			if (records != null) {
				while (records.hasNext()) {
					PoiRecord record = records.next();
					if (occupancy.test(record)) {
						BlockPos pos = record.getPos();
						if (Math.abs(pos.getX() - centerX) <= radius && Math.abs(pos.getZ() - centerZ) <= radius) {
							action.accept(record);
							return true;
						}
					}
				}
				recordIterator = null;
			}
			Iterator<Map.Entry<Holder<PoiType>, Set<PoiRecord>>> entries = typeIterator;
			if (entries != null) {
				while (entries.hasNext()) {
					Map.Entry<Holder<PoiType>, Set<PoiRecord>> entry = entries.next();
					if (types.test(entry.getKey())) {
						recordIterator = entry.getValue().iterator();
						break;
					}
				}
				if (recordIterator != null) continue;
				typeIterator = null;
			}
			if (!nextSection()) return false;
		}
	}

	/** Moves to the next section that exists, false when there are none left. */
	private boolean nextSection() {
		while (true) {
			if (section >= sectionCount && !nextColumn()) return false;
			int index = section++;
			Optional<?> found = column != null ? column[index] : null;
			if (found == null) {
				found = storage.afterburner$getOrLoad(SectionPos.asLong(chunkX, minSection + index, chunkZ));
				column = storage.afterburner$column(ChunkPos.pack(chunkX, chunkZ));
			}
			if (found.isPresent()) {
				typeIterator = ((PoiSectionAccessor) (PoiSection) found.get()).afterburner$byType().entrySet().iterator();
				return true;
			}
		}
	}

	private boolean nextColumn() {
		if (!started) {
			started = true;
			chunkX = minChunkX;
			chunkZ = minChunkZ;
		} else if (chunkX == maxChunkX) {
			if (chunkZ == maxChunkZ) return false;
			chunkX = minChunkX;
			chunkZ++;
		} else {
			chunkX++;
		}
		section = 0;
		column = storage.afterburner$column(ChunkPos.pack(chunkX, chunkZ));
		return true;
	}
}
