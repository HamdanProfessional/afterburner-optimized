package com.afterburner.mixin.light;

import com.afterburner.light.LightStorageAccess;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Every light value written adds its section to two hash sets: the sections changed this round (their data gets copied
 * before the first write) and the sections whose chunk meshes need rebuilding (with the sections next to it, for a
 * block on the edge). Light writes many values into the same few sections, so this remembers which sections were
 * already added since the sets were last emptied, one per slot of a 4x4x4 grid, and skips adding them again.
 */
@Mixin(LayerLightSectionStorage.class)
public abstract class LayerLightSectionStorageMixin<M extends DataLayerStorageMap<M>> implements LightStorageAccess {
	@Unique
	private static final long NO_KEY = Long.MAX_VALUE;

	@Shadow
	@Final
	protected M updatingSectionData;
	@Shadow
	@Final
	protected LongSet changedSections;
	@Shadow
	@Final
	protected LongSet sectionsAffectedByLightUpdates;

	/** Sections known to be in {@link #changedSections}, one per slot of a 4x4x4 grid (made on first use). */
	@Unique
	private long @Nullable [] afterburner$changed;
	/** Sections known to be in {@link #sectionsAffectedByLightUpdates}. */
	@Unique
	private long @Nullable [] afterburner$affected;

	@Shadow
	protected abstract @Nullable DataLayer getDataLayer(long sectionNode, boolean updating);

	@Unique
	private static long[] afterburner$noKeys() {
		long[] keys = new long[64];
		Arrays.fill(keys, NO_KEY);
		return keys;
	}

	@Unique
	private static int afterburner$slot(int sectionX, int sectionY, int sectionZ) {
		return (sectionX & 3) << 4 | (sectionZ & 3) << 2 | (sectionY & 3);
	}

	/**
	 * @author Afterburner
	 * @reason Skip the hash sets for sections already added to them.
	 */
	@Overwrite
	protected void setStoredLevel(long blockNode, int level) {
		int x = BlockPos.getX(blockNode), y = BlockPos.getY(blockNode), z = BlockPos.getZ(blockNode);
		int sx = SectionPos.blockToSectionCoord(x), sy = SectionPos.blockToSectionCoord(y), sz = SectionPos.blockToSectionCoord(z);
		long sectionNode = SectionPos.asLong(sx, sy, sz);
		int slot = afterburner$slot(sx, sy, sz);
		long[] changed = afterburner$changed, affected = afterburner$affected;
		if (changed == null) changed = afterburner$changed = afterburner$noKeys();
		if (affected == null) affected = afterburner$affected = afterburner$noKeys();
		DataLayer layer;
		if (changed[slot] != sectionNode && changedSections.add(sectionNode)) {
			layer = updatingSectionData.copyDataLayer(sectionNode);
		} else {
			layer = getDataLayer(sectionNode, true);
		}
		changed[slot] = sectionNode;
		layer.set(x & 15, y & 15, z & 15, level);
		// The section, and the ones next to it if the block is on its edge (what SectionPos.aroundAndAtBlockPos gives).
		int maxX = SectionPos.blockToSectionCoord(x + 1), maxY = SectionPos.blockToSectionCoord(y + 1), maxZ = SectionPos.blockToSectionCoord(z + 1);
		for (int ax = SectionPos.blockToSectionCoord(x - 1); ax <= maxX; ax++) {
			for (int ay = SectionPos.blockToSectionCoord(y - 1); ay <= maxY; ay++) {
				for (int az = SectionPos.blockToSectionCoord(z - 1); az <= maxZ; az++) {
					long node = SectionPos.asLong(ax, ay, az);
					int s = afterburner$slot(ax, ay, az);
					if (affected[s] != node) {
						sectionsAffectedByLightUpdates.add(node);
						affected[s] = node;
					}
				}
			}
		}
	}

	@Override
	public @Nullable DataLayer afterburner$layer(long sectionNode) {
		return getDataLayer(sectionNode, true);
	}

	@Override
	public void afterburner$set(long blockNode, int level) {
		setStoredLevel(blockNode, level);
	}

	@Inject(method = "swapSectionMap", at = @At("HEAD"))
	private void afterburner$onSwap(CallbackInfo ci) {
		// Both sets are emptied here.
		if (afterburner$changed != null) Arrays.fill(afterburner$changed, NO_KEY);
		if (afterburner$affected != null) Arrays.fill(afterburner$affected, NO_KEY);
	}
}
