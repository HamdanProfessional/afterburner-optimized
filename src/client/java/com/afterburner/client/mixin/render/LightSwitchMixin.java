package com.afterburner.client.mixin.render;

import com.afterburner.client.render.SectionScans;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The client's light engine turning a chunk's light on or off ({@link SectionScans#lightOn}); the server's is a subclass. */
@Mixin(LevelLightEngine.class)
public class LightSwitchMixin {
	@Inject(method = "setLightEnabled", at = @At("HEAD"))
	private void afterburner$switched(ChunkPos pos, boolean enabled, CallbackInfo ci) {
		if (((Object) this).getClass() == LevelLightEngine.class) SectionScans.lightSwitched();
	}
}
