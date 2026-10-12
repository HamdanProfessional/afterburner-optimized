package com.afterburner.mixin.light;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

/**
 * Light moving through the world reads the light data of the same few sections over and over. Vanilla remembers the
 * last two it looked up, so light crossing between sections keeps going back to the hash map. This remembers one
 * section per slot of a 4x4x4 grid instead, which holds every section around a spot without them pushing each other
 * out, and also remembers sections that have no data.
 */
@Mixin(DataLayerStorageMap.class)
public abstract class DataLayerStorageMapMixin {
	@Unique
	private static final int SLOTS = 64;
	@Unique
	private static final long NO_KEY = Long.MAX_VALUE;

	@Shadow
	@Final
	protected Long2ObjectOpenHashMap<DataLayer> map;
	@Shadow
	private boolean cacheEnabled;

	/** Created on first use, so the copies made for other threads (which don't use a cache) don't get one. */
	@Unique
	private long @Nullable [] afterburner$keys;
	@Unique
	private DataLayer @Nullable [] afterburner$layers;

	/** Slot of a section, from the lowest two bits of its x, y and z. */
	@Unique
	private static int afterburner$slot(long sectionNode) {
		return (int) (sectionNode >>> 42 & 3) << 4 | (int) (sectionNode >>> 20 & 3) << 2 | (int) (sectionNode & 3);
	}

	/**
	 * @author Afterburner
	 * @reason Remember more sections than two, and sections without data.
	 */
	@Overwrite
	public @Nullable DataLayer getLayer(long sectionNode) {
		if (!cacheEnabled) return map.get(sectionNode);
		if (afterburner$keys == null) {
			afterburner$keys = new long[SLOTS];
			afterburner$layers = new DataLayer[SLOTS];
			Arrays.fill(afterburner$keys, NO_KEY);
		}
		int slot = afterburner$slot(sectionNode);
		if (afterburner$keys[slot] == sectionNode) return afterburner$layers[slot];
		DataLayer layer = map.get(sectionNode);
		afterburner$keys[slot] = sectionNode;
		afterburner$layers[slot] = layer;
		return layer;
	}

	/**
	 * @author Afterburner
	 * @reason Vanilla forgets every remembered section here, but only the copied one changed.
	 */
	@Overwrite
	public DataLayer copyDataLayer(long sectionNode) {
		DataLayer copy = map.get(sectionNode).copy();
		map.put(sectionNode, copy);
		afterburner$forget(sectionNode);
		return copy;
	}

	@Inject(method = "setLayer", at = @At("HEAD"))
	private void afterburner$onSet(long sectionNode, DataLayer layer, CallbackInfo ci) {
		afterburner$forget(sectionNode);
	}

	@Inject(method = "removeLayer", at = @At("HEAD"))
	private void afterburner$onRemove(long sectionNode, CallbackInfoReturnable<DataLayer> cir) {
		afterburner$forget(sectionNode);
	}

	@Inject(method = "clearCache", at = @At("TAIL"))
	private void afterburner$onClear(CallbackInfo ci) {
		afterburner$clear();
	}

	@Unique
	private void afterburner$forget(long sectionNode) {
		if (afterburner$keys != null) afterburner$keys[afterburner$slot(sectionNode)] = NO_KEY;
	}

	@Unique
	private void afterburner$clear() {
		if (afterburner$keys == null) return;
		Arrays.fill(afterburner$keys, NO_KEY);
		Arrays.fill(afterburner$layers, null);
	}
}
