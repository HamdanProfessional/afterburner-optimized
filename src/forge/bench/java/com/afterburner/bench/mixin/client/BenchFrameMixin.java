package com.afterburner.bench.mixin.client;

import com.afterburner.bench.client.AutoBench;
import com.afterburner.bench.client.FrameRecorder;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class BenchFrameMixin {
	/** runTick runs once per frame, straight from the game loop. */
	@Inject(method = "runTick", at = @At("HEAD"))
	private void afterburner$benchFrame(boolean advanceGameTime, CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		AutoBench.onFrame(mc);
		FrameRecorder.onFrame(mc);
	}
}
