package com.afterburner.client.mixin.perf;

import com.afterburner.client.perf.FpsTarget;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Clouds left out while {@link FpsTarget} holds them off; the option itself (and what's saved) stays as set. */
@Mixin(Options.class)
public class FpsTargetCloudsMixin {
	@ModifyReturnValue(method = "getCloudStatus", at = @At("RETURN"))
	private CloudStatus afterburner$clouds(CloudStatus status) {
		return FpsTarget.cloudsOff() ? CloudStatus.OFF : status;
	}
}
