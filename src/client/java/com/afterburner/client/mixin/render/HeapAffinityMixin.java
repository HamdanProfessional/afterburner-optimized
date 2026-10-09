package com.afterburner.client.mixin.render;

import com.afterburner.client.mixin.memory.HeapAccessor;
import com.afterburner.client.render.RegionMesh;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Puts the meshes of a region's sections in the same piece of chunk buffer memory while it has room, where vanilla takes the
 * first piece with room for each. A batch draws from one piece, so a region spread over several took a draw call per piece,
 * and water and glass (drawn in order, so only neighbours in that order share a call) mostly took one per section. A region's
 * first mesh, or one that doesn't fit its piece any more, goes to the piece with the most room, so the rest fit there too.
 */
@Mixin(UberGpuBuffer.class)
public abstract class HeapAffinityMixin {
	/** {@code -Dafterburner.heapAffinity=false} places meshes as vanilla does, to compare. */
	@Unique
	private static final boolean AFFINITY = !"false".equals(System.getProperty("afterburner.heapAffinity"));
	@Unique
	private static final long NONE = RegionMesh.NO_REGION;
	/** Regions remembered at most; past that they're all forgotten (flying far leaves many behind). */
	@Unique
	private static final int MAX_REGIONS = 4096;

	@Shadow
	@Final
	private List<Pair<TlsfAllocator, UberGpuBuffer.UberGpuBufferHeap>> nodes;

	/** Per region: the piece its last mesh went to. Uploads are on the render thread, under the dispatcher's lock. */
	@Unique
	private final Long2ObjectOpenHashMap<TlsfAllocator> afterburner$regionHeaps = new Long2ObjectOpenHashMap<>();
	/** Bytes taken in each piece. */
	@Unique
	private final Reference2LongOpenHashMap<TlsfAllocator> afterburner$used = new Reference2LongOpenHashMap<>();
	/** Region of the mesh being placed, until it has a place; and whether its region's piece was tried. */
	@Unique
	private long afterburner$placing = NONE;
	@Unique
	private boolean afterburner$tried;

	/** Called for each staged mesh right before it's placed: which region it's in. */
	@WrapOperation(method = "uploadStagedAllocations", at = @At(value = "INVOKE",
			target = "Lit/unimi/dsi/fastutil/objects/ObjectOpenHashSet;contains(Ljava/lang/Object;)Z"))
	private boolean afterburner$placing(ObjectOpenHashSet<Object> skipped, Object key, Operation<Boolean> original) {
		afterburner$placing = AFFINITY && key instanceof RegionMesh mesh ? mesh.afterburner$regionKey() : NONE;
		afterburner$tried = false;
		return original.call(skipped, key);
	}

	@WrapOperation(method = "uploadStagedAllocations", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/TlsfAllocator;allocate(JI)Lcom/mojang/blaze3d/vertex/TlsfAllocator$Allocation;"))
	private TlsfAllocator.@Nullable Allocation afterburner$nearRegion(TlsfAllocator allocator, long size, int align,
			Operation<TlsfAllocator.@Nullable Allocation> original) {
		long region = afterburner$placing;
		if (region != NONE && !afterburner$tried) {
			// Vanilla tries every piece in turn from here on; this is the first try for the mesh.
			afterburner$tried = true;
			TlsfAllocator preferred = afterburner$regionHeaps.get(region);
			if (preferred == null || !afterburner$inUse(preferred)) preferred = afterburner$roomiest();
			if (preferred != null) {
				TlsfAllocator.Allocation allocation = original.call(preferred, size, align);
				if (allocation == null) {
					TlsfAllocator roomiest = afterburner$roomiest();
					if (roomiest != null && roomiest != preferred) {
						preferred = roomiest;
						allocation = original.call(preferred, size, align);
					}
				}
				if (allocation != null) return afterburner$placed(preferred, allocation);
			}
		}
		TlsfAllocator.Allocation allocation = original.call(allocator, size, align);
		return allocation != null ? afterburner$placed(allocator, allocation) : null;
	}

	@WrapOperation(method = "freeAllocation", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/TlsfAllocator;free(Lcom/mojang/blaze3d/vertex/TlsfAllocator$Allocation;)V"))
	private void afterburner$freed(TlsfAllocator allocator, TlsfAllocator.Allocation allocation, Operation<Void> original) {
		// Its size before it's merged with the free space around it.
		if (!allocation.isFreed()) afterburner$used.addTo(allocator, -allocation.getSize());
		original.call(allocator, allocation);
	}

	@Unique
	private TlsfAllocator.Allocation afterburner$placed(TlsfAllocator allocator, TlsfAllocator.Allocation allocation) {
		afterburner$used.addTo(allocator, allocation.getSize());
		long region = afterburner$placing;
		afterburner$placing = NONE;
		if (region != NONE) {
			if (afterburner$regionHeaps.size() >= MAX_REGIONS) afterburner$regionHeaps.clear();
			afterburner$regionHeaps.put(region, allocator);
		}
		return allocation;
	}

	/** The piece with the most bytes free, or null if there's none yet. */
	@Unique
	private @Nullable TlsfAllocator afterburner$roomiest() {
		TlsfAllocator best = null;
		long most = Long.MIN_VALUE;
		for (Pair<TlsfAllocator, UberGpuBuffer.UberGpuBufferHeap> node : nodes) {
			long free = ((HeapAccessor) node.getSecond()).afterburner$size() - afterburner$used.getLong(node.getFirst());
			if (free > most) {
				most = free;
				best = node.getFirst();
			}
		}
		// Pieces let go of (left empty) are forgotten now and then.
		if (afterburner$used.size() > nodes.size() + 8) afterburner$used.keySet().removeIf(a -> !afterburner$inUse(a));
		return best;
	}

	/** Whether the piece is still there: one left empty is let go of. */
	@Unique
	private boolean afterburner$inUse(TlsfAllocator allocator) {
		for (Pair<TlsfAllocator, UberGpuBuffer.UberGpuBufferHeap> node : nodes) {
			if (node.getFirst() == allocator) return true;
		}
		return false;
	}
}
