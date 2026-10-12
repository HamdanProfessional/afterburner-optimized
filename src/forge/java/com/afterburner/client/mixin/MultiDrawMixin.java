package com.afterburner.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla never uses multi-draw for terrain on Intel graphics (except Arc) because of driver bugs, and draws every
 * chunk section separately instead. {@code -Dafterburner.intelMultiDraw=true} turns it back on, for testing.
 */
@Mixin(LevelRenderer.class)
public class MultiDrawMixin {
	@WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/HintsAndWorkarounds;multiDrawIndirectHasKnownIssues()Z"))
	private boolean afterburner$allowIntelMultiDraw(HintsAndWorkarounds hints, Operation<Boolean> original) {
		return !Boolean.getBoolean("afterburner.intelMultiDraw") && original.call(hints);
	}
}
