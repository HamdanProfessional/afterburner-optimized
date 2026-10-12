package com.afterburner.client.mixin;

import com.afterburner.client.memory.MemoryTrim;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
	/** runTick runs once per frame, straight from the game loop. */
	@Inject(method = "runTick", at = @At("HEAD"))
	private void afterburner$onFrame(boolean advanceGameTime, CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		MemoryTrim.onFrame(mc);
	}
}
