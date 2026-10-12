package com.afterburner.client.mixin.render;

import com.afterburner.client.render.TranslucentCulling;
import com.mojang.blaze3d.vertex.CompactVectorArray;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Keeps the facing of each quad next to its center (see {@link TranslucentCulling}). */
@Mixin(CompactVectorArray.class)
public class TranslucentCentersMixin implements TranslucentCulling.Facings {
	@Unique
	private byte @Nullable [] afterburner$facings;

	@Override
	public byte @Nullable [] afterburner$facings() {
		return afterburner$facings;
	}

	@Override
	public void afterburner$setFacings(byte[] facings) {
		afterburner$facings = facings;
	}
}
