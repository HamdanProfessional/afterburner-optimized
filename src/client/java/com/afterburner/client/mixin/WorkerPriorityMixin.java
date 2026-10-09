package com.afterburner.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.ForkJoinWorkerThread;

/**
 * The game's background workers (chunk generation, lighting, chunk mesh building) run just below normal priority, so
 * when every core is busy the frames, the game's server and other programs come first. Vanilla already puts the
 * render and server threads above normal on PCs with more than 4 cores; on smaller ones everything was equal. Windows
 * honors this; on Linux Java ignores thread priorities, so nothing changes there.
 * <p>
 * 1.21.1 names the worker factory lambda {@code method_28123} (in 26.3 it was an overridden {@code onStart}).
 */
@Mixin(Util.class)
public class WorkerPriorityMixin {
	@ModifyReturnValue(method = "method_28123", at = @At("RETURN"))
	private static ForkJoinWorkerThread afterburner$lowerPriority(ForkJoinWorkerThread thread) {
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	}
}
