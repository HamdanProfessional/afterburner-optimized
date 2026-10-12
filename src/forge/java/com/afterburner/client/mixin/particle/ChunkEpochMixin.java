package com.afterburner.client.mixin.particle;

import com.afterburner.client.render.ParticleCulling;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A chunk coming or going on the client changes the light particles there get (none without a chunk): kept light is old then. */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache$Storage")
public class ChunkEpochMixin {
	@Inject(method = {"replace", "drop"}, at = @At("HEAD"))
	private void afterburner$changed(CallbackInfo ci) {
		ParticleCulling.lightEpoch++;
	}
}
