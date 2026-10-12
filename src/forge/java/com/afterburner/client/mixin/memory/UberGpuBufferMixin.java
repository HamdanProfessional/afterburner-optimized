package com.afterburner.client.mixin.memory;

import com.afterburner.client.memory.ChunkBufferSizes;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.datafixers.util.Pair;
import com.mojang.renderpearl.api.device.GpuDevice;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** New pieces of chunk mesh memory are sized by {@link ChunkBufferSizes} instead of always being 128 MB. */
@Mixin(UberGpuBuffer.class)
public abstract class UberGpuBufferMixin {
	@Shadow
	@Final
	private List<Pair<TlsfAllocator, UberGpuBuffer.UberGpuBufferHeap>> nodes;

	@WrapOperation(method = "uploadStagedAllocations", at = @At(value = "NEW", target = "com/mojang/blaze3d/vertex/UberGpuBuffer$UberGpuBufferHeap"))
	private UberGpuBuffer.UberGpuBufferHeap afterburner$heap(long size, GpuDevice device, int usage, String name,
			Operation<UberGpuBuffer.UberGpuBufferHeap> original, @Local long allocationSize) {
		long reserved = 0;
		for (Pair<TlsfAllocator, UberGpuBuffer.UberGpuBufferHeap> node : nodes) reserved += ((HeapAccessor) node.getSecond()).afterburner$size();
		return original.call(ChunkBufferSizes.heapSize(size, reserved, allocationSize), device, usage, name);
	}
}
