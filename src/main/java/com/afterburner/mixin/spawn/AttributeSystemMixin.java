package com.afterburner.mixin.spawn;

import com.afterburner.spawn.SpawnCosts;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Map;

/** Each world's attributes keep what {@link SpawnCosts} found out about them. */
@Mixin(EnvironmentAttributeSystem.class)
public abstract class AttributeSystemMixin implements SpawnCosts.Known {
	@Shadow
	@Final
	private Map<EnvironmentAttribute<?>, ?> attributeSamplers;
	@Unique
	private volatile @Nullable SpawnCosts afterburner$spawnCosts;

	@Override
	public Map<EnvironmentAttribute<?>, ?> afterburner$samplers() {
		return attributeSamplers;
	}

	@Override
	public @Nullable SpawnCosts afterburner$spawnCosts() {
		return afterburner$spawnCosts;
	}

	@Override
	public void afterburner$spawnCosts(SpawnCosts costs) {
		afterburner$spawnCosts = costs;
	}
}
