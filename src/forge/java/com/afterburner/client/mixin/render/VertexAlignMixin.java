package com.afterburner.client.mixin.render;

import com.afterburner.client.render.CompactVertices;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chunk meshes of vanilla (28-byte) and {@link CompactVertices} (16-byte) vertices share a buffer, and a draw can only
 * start at a whole vertex, so meshes are placed at multiples of both: 112 bytes.
 */
@Mixin(UberGpuBuffer.class)
public class VertexAlignMixin {
	@Shadow
	@Final
	@Mutable
	private int alignSize;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void afterburner$alignForBoth(CallbackInfo ci) {
		if (alignSize == 28) alignSize = 28 * CompactVertices.SIZE / gcd(28, CompactVertices.SIZE);
	}

	@Unique
	private static int gcd(int a, int b) {
		return b == 0 ? a : gcd(b, a % b);
	}
}
