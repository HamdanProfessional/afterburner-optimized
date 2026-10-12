package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedSprites;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The sections on screen keep their animated textures moving (see {@link AnimatedSprites}). */
@Mixin(LevelRenderer.class)
public class AnimatedVisibleMixin {
	@Shadow
	@Final
	private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

	@Inject(method = "applyFrustum", at = @At("TAIL"))
	private void afterburner$shown(Frustum frustum, CallbackInfo ci) {
		AnimatedSprites.setVisible(visibleSections);
	}
}
