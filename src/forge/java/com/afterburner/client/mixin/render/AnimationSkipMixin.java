package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedSprites;
import com.afterburner.client.render.TrackedAnimation;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A block atlas texture that isn't on screen keeps counting its frames but isn't drawn into the atlas (see {@link AnimatedSprites}). */
@Mixin(SpriteContents.AnimationState.class)
public class AnimationSkipMixin implements TrackedAnimation {
	@Shadow
	private boolean isDirty;
	@Unique
	private @Nullable SpriteContents afterburner$contents;
	@Unique
	private boolean afterburner$skip, afterburner$stale;

	@Override
	public void afterburner$track(SpriteContents contents) {
		afterburner$contents = contents;
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void afterburner$skipUnseen(CallbackInfo ci) {
		if (afterburner$contents == null) return;
		afterburner$skip = false;
		boolean draws = ((SpriteContents.AnimationState) (Object) this).needsToDraw();
		if (AnimatedSprites.skips(afterburner$contents)) {
			afterburner$skip = true;
			afterburner$stale |= draws;
			if (draws) AnimatedSprites.skipped++;
			return;
		}
		if (afterburner$stale) {
			// Back on screen after frames went by unseen: draws the one it's at now.
			isDirty = true;
			afterburner$stale = false;
		}
		if (isDirty || draws) AnimatedSprites.redrawn++;
	}

	@Inject(method = "needsToDraw", at = @At("HEAD"), cancellable = true)
	private void afterburner$skip(CallbackInfoReturnable<Boolean> cir) {
		if (afterburner$skip) cir.setReturnValue(false);
	}
}
