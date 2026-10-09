package com.afterburner.client.mixin.memory;

import com.mojang.blaze3d.vertex.TlsfAllocator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TlsfAllocator.Heap.class)
public interface HeapAccessor {
	@Accessor("size")
	long afterburner$size();
}
