package com.afterburner.client.mixin.perf;

import com.afterburner.client.perf.FpsTarget;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** The render distance fog comes in to where {@link FpsTarget} draws chunks to. */
@Mixin(GameRenderer.class)
public class FpsTargetFogMixin {
	@ModifyArg(method = "extractCamera", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/fog/FogRenderer;setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/multiplayer/ClientLevel;)Lnet/minecraft/client/renderer/fog/FogData;"),
			index = 1)
	private int afterburner$fogDistance(int renderDistance) {
		return FpsTarget.fogDistance(renderDistance);
	}
}
