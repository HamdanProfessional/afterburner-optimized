package com.afterburner.client.mixin.render;

import com.afterburner.client.render.WideSections;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** When the occlusion graph tells vanilla to pick its visible sections again, the chunk batcher's wide ones are picked again too. */
@Mixin(SectionOcclusionGraph.class)
public class WideSectionsMixin {
	@ModifyReturnValue(method = "consumeFrustumUpdate", at = @At("RETURN"))
	private boolean afterburner$graphChanged(boolean changed) {
		if (changed) WideSections.graphChanged();
		return changed;
	}
}
