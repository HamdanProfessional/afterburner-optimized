package com.afterburner.bench.mixin.client;

import com.afterburner.bench.client.BuildLog;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts blocks changed on the client (each marks its section to be built again), for {@link BuildLog}. */
@Mixin(LevelRenderer.class)
public class BuildLogBlockMixin {
	@Inject(method = "blockChanged", at = @At("HEAD"))
	private void afterburner$block(CallbackInfo ci) {
		if (BuildLog.ENABLED) BuildLog.blockMarks++;
	}
}
