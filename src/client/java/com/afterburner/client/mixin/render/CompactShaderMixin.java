package com.afterburner.client.mixin.render;

import com.afterburner.client.render.CompactVertices;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes the compact chunk shaders ({@link CompactVertices}) whenever the game's shaders are loaded. */
@Mixin(GameRenderer.class)
public class CompactShaderMixin {
	@Inject(method = "reloadShaders", at = @At("TAIL"))
	private void afterburner$compactShaders(ResourceProvider provider, CallbackInfo ci) {
		CompactVertices.reload(provider);
	}
}
