package com.afterburner.client.mixin.render;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Makes vanilla's smooth texture filtering (RGSS) read the texture 4 times per pixel instead of 9, for the same picture.
 * <ul>
 * <li>Vanilla blends a plain read with 8 smooth ones, with weights that are often exactly 0 or 1: up close only the plain
 * read counts, far away only the smooth ones. The reads whose weight is 0 are skipped.</li>
 * <li>The 8 smooth reads are 4 spots, each read from two mipmap levels and blended by how far between them the pixel is.
 * The block atlas sampler blends mipmap levels itself (linear mipmap filtering), so reading each spot once at the
 * in-between level gives the same color with half the reads.</li>
 * </ul>
 * If a resource pack changed the file, it's left alone.
 */
@Mixin(ShaderSource.CachedIncludeSource.class)
public class FilteringShortcutMixin {
	@ModifyVariable(method = "create", at = @At("HEAD"), argsOnly = true)
	private static String afterburner$fewerReads(String source, Identifier id) {
		if (!id.getPath().equals("texture_sampling.glsl") || !id.getNamespace().equals("minecraft")) return source;
		String blend = "float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);";
		String nearest = "vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);";
		String readLow = "rgssColorLow += textureLod(source, sampleUV, mipLevelLow);";
		String readHigh = "rgssColorHigh += textureLod(source, sampleUV, mipLevelHigh);";
		String mix = "vec4 rgssColor = mix(rgssColorLow, rgssColorHigh, mipBlend);";
		for (String part : new String[] {blend, nearest, readLow, readHigh, mix}) {
			if (!source.contains(part)) return source;
		}
		return source
				.replace(blend, blend + " if (blendFactor <= 0.0) return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);")
				.replace(readLow, "rgssColorLow += textureLod(source, sampleUV, mipLevelExact);")
				.replace(readHigh, "")
				.replace(mix, "vec4 rgssColor = rgssColorLow;")
				.replace(nearest, "if (blendFactor >= 1.0) return rgssColor; " + nearest);
	}
}
