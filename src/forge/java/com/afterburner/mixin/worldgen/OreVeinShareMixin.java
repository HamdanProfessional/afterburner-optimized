package com.afterburner.mixin.worldgen;

import com.afterburner.worldgen.OreVeinShare;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.MaterialRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.HashMap;
import java.util.Map;

/**
 * Each kind of ore vein (copper, iron) works out its noises for the whole chunk before its blocks are placed, and both use
 * the same richness noise: it was worked out twice per chunk. The same sampler over the same chunk gives the same numbers,
 * so the second asks get the first one's (equal functions compile to one sampler, and a bound sampler is that and the
 * chunk's context).
 */
@Mixin(MaterialRuleContext.class)
public abstract class OreVeinShareMixin {
	@Shadow
	@Final
	private DensitySamplerSet densitySamplers;
	@Unique
	private final Map<DensitySampler.Bound, MaterialRules.DensityGetter> afterburner$filled = new HashMap<>(4);

	@WrapMethod(method = "getDensitiesInChunk")
	private MaterialRules.DensityGetter afterburner$fillOnce(DensityFunction function, boolean prefill, Operation<MaterialRules.DensityGetter> original) {
		if (!prefill) return original.call(function, false);
		DensitySampler.Bound sampler = densitySamplers.get(function);
		MaterialRules.DensityGetter filled = afterburner$filled.get(sampler);
		if (filled == null) {
			afterburner$filled.put(sampler, filled = original.call(function, true));
			return filled;
		}
		if (!OreVeinShare.CHECK) return filled;
		OreVeinShare.shared();
		MaterialRules.DensityGetter shared = filled, vanilla = original.call(function, true);
		return () -> OreVeinShare.compare(shared.get(), vanilla.get());
	}
}
