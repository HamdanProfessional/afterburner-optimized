package com.afterburner.bench.mixin.client;

import com.afterburner.bench.client.FrameSections;
import net.minecraft.client.Minecraft;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.SingleTickProfiler;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds {@link FrameSections} to the render thread's profiler while a benchmark measures. */
@Mixin(Minecraft.class)
public class FrameSectionsMixin {
	@Inject(method = "constructProfiler", at = @At("RETURN"), cancellable = true)
	private void afterburner$frameSections(boolean shouldCollectFrameProfile, @Nullable SingleTickProfiler tickProfiler, CallbackInfoReturnable<ProfilerFiller> cir) {
		if (FrameSections.measuring) cir.setReturnValue(ProfilerFiller.combine(cir.getReturnValue(), FrameSections.INSTANCE));
	}
}
