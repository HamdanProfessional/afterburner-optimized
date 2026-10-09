package com.afterburner.mixin.tick;

import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.SimulationChunkTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DistanceManager.class)
public interface DistanceManagerAccessor {
	@Accessor("simulationChunkTracker")
	SimulationChunkTracker afterburner$simulation();
}
