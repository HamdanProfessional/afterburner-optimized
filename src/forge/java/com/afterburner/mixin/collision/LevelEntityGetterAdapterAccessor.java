package com.afterburner.mixin.collision;

import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelEntityGetterAdapter.class)
public interface LevelEntityGetterAdapterAccessor<T extends EntityAccess> {
	@Accessor("sectionStorage")
	EntitySectionStorage<T> afterburner$sections();
}
