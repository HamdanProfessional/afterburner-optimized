package com.afterburner.mixin.ai;

import com.afterburner.ai.AiCheck;
import com.afterburner.ai.PoiColumns;
import com.afterburner.ai.PoiSquare;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Spliterators;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Villagers, bees, raids, lightning rods and portals all search for points of interest (job blocks, beds, bells,
 * hives...) through this, and villagers do it often. Same answers in the same order, see {@link PoiSquare}.
 */
@Mixin(PoiManager.class)
public abstract class PoiManagerMixin {
	@WrapMethod(method = "getInSquare")
	private Stream<PoiRecord> afterburner$getInSquare(Predicate<Holder<PoiType>> predicate, BlockPos center, int radius,
			PoiManager.Occupancy occupancy, Operation<Stream<PoiRecord>> original) {
		if (radius < 0) return original.call(predicate, center, radius, occupancy);
		PoiSquare square = new PoiSquare((PoiColumns) this, predicate, center, radius, occupancy.getTest());
		if (!AiCheck.ENABLED) return StreamSupport.stream(square, false);
		List<PoiRecord> expected = original.call(predicate, center, radius, occupancy).toList();
		List<PoiRecord> found = new ArrayList<>();
		square.forEachRemaining(found::add);
		AiCheck.result("poi", expected.equals(found), "vanilla " + expected.size() + " records, got " + found.size() + " around " + center);
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(found.iterator(), 0), false);
	}
}
