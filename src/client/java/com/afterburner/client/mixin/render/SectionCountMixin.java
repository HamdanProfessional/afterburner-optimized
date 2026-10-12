package com.afterburner.client.mixin.render;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The F3 screen's count of drawn sections looks at every visible section's build, every frame it's open (at render
 * distance 32 that was 6% of the render thread): counted again 4 times a second instead.
 */
@Mixin(LevelRenderer.class)
public class SectionCountMixin {
	@Unique
	private int afterburner$count;
	@Unique
	private long afterburner$countedAt;

	@Inject(method = "countRenderedSections", at = @At("HEAD"), cancellable = true)
	private void afterburner$recent(CallbackInfoReturnable<Integer> cir) {
		if (System.nanoTime() - afterburner$countedAt < 250_000_000L) cir.setReturnValue(afterburner$count);
	}

	@Inject(method = "countRenderedSections", at = @At("RETURN"))
	private void afterburner$counted(CallbackInfoReturnable<Integer> cir) {
		afterburner$count = cir.getReturnValue();
		afterburner$countedAt = System.nanoTime();
	}
}
