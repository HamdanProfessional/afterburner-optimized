package com.afterburner.bench.mixin.client;

import com.afterburner.bench.client.BuildLog;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts what marks chunk sections for building, for {@link BuildLog}. */
@Mixin(LevelExtractor.class)
public class BuildLogMixin {
	@Inject(method = "setSectionRangeDirty", at = @At("HEAD"))
	private void afterburner$chunk(int x0, int y0, int z0, int x1, int y1, int z1, CallbackInfo ci) {
		if (BuildLog.ENABLED) BuildLog.chunkMarks++;
	}

	@Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"))
	private void afterburner$light(int x, int y, int z, CallbackInfo ci) {
		if (BuildLog.ENABLED) BuildLog.lightMarks++;
	}

	@Inject(method = "blockChanged", at = @At("HEAD"))
	private void afterburner$block(BlockPos pos, int flags, CallbackInfo ci) {
		if (BuildLog.ENABLED) BuildLog.blockMarks++;
	}
}
