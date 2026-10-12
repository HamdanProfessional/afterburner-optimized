package com.afterburner.client.mixin.particle;

import com.afterburner.client.render.ParticleCulling;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A chunk loaded or let go of on the client changes the light particles are drawn with (see {@link ParticleCulling}). */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache$Storage")
public class ChunkEpochMixin {
	@Inject(method = "replace(ILnet/minecraft/world/level/chunk/LevelChunk;)V", at = @At("HEAD"))
	private void afterburner$replaced(CallbackInfo ci) {
		ParticleCulling.lightEpoch++;
	}

	@Inject(method = "replace(ILnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/chunk/LevelChunk;)Lnet/minecraft/world/level/chunk/LevelChunk;",
			at = @At("HEAD"))
	private void afterburner$dropped(CallbackInfoReturnable<?> cir) {
		ParticleCulling.lightEpoch++;
	}
}
