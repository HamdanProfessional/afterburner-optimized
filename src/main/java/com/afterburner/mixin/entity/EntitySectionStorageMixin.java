package com.afterburner.mixin.entity;

import com.afterburner.entity.SectionSearch;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Entity searches look up the few sections a box reaches instead of walking every section along x, see {@link SectionSearch}. */
@Mixin(EntitySectionStorage.class)
public abstract class EntitySectionStorageMixin<T extends EntityAccess> {
	@Shadow
	@Final
	private Long2ObjectMap<EntitySection<T>> sections;
	@Shadow
	@Final
	private LongSortedSet sectionIds;

	@Inject(method = "forEachAccessibleNonEmptySection", at = @At("HEAD"), cancellable = true)
	private void afterburner$forEach(AABB bb, AbortableIterationConsumer<EntitySection<T>> output, CallbackInfo ci) {
		SectionSearch.forEach(sections, sectionIds, bb, output);
		ci.cancel();
	}
}
