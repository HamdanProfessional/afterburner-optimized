package com.afterburner.client.mixin.particle;

import com.afterburner.client.render.ParticleCulling;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every block and light change on the client marks its chunk section for a rebuild here: kept particle light is old then. */
@Mixin(LevelExtractor.class)
public class LightEpochMixin {
	@Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
	private void afterburner$changed(CallbackInfo ci) {
		ParticleCulling.lightEpoch++;
	}
}
