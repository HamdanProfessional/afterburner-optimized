package com.afterburner.mixin.worldgen;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ore veins read and write their blocks through this, and every block asks the world for its height twice (a chain of
 * calls down to the dimension each time). The height doesn't change, so it is asked once.
 */
@Mixin(BulkSectionAccess.class)
public abstract class BulkSectionAccessMixin {
	@Unique
	private int afterburner$indexOffset, afterburner$sections;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void afterburner$height(LevelAccessor level, CallbackInfo ci) {
		afterburner$indexOffset = level.getSectionIndexFromSectionY(0);
		afterburner$sections = level.getSectionsCount();
	}

	@Redirect(method = "getSection", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;getSectionIndex(I)I"))
	private int afterburner$sectionIndex(LevelAccessor level, int blockY) {
		return SectionPos.blockToSectionCoord(blockY) + afterburner$indexOffset;
	}

	@Redirect(method = "getSection", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;getSectionsCount()I"))
	private int afterburner$sectionsCount(LevelAccessor level) {
		return afterburner$sections;
	}
}
