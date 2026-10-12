package com.afterburner.bench.mixin.client;

import com.afterburner.bench.client.BuildLog;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts sections marked to be built again because a chunk came in or its light did, for {@link BuildLog}. */
@Mixin(ClientPacketListener.class)
public class BuildLogMixin {
	@Inject(method = "enableChunkLight", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/ClientLevel;setSectionDirtyWithNeighbors(III)V"))
	private void afterburner$chunk(CallbackInfo ci) {
		if (BuildLog.ENABLED) BuildLog.chunkMarks++;
	}

	@Inject(method = "readSectionList", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/ClientLevel;setSectionDirtyWithNeighbors(III)V"))
	private void afterburner$light(CallbackInfo ci) {
		if (BuildLog.ENABLED) BuildLog.lightMarks++;
	}
}
