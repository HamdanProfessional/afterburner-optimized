package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedSprites;
import com.afterburner.client.render.SeenSprite;
import com.afterburner.client.render.TrackedAnimation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A block atlas texture that isn't on screen keeps counting its frames but isn't uploaded into the atlas (see {@link AnimatedSprites}). */
@Mixin(SpriteContents.Ticker.class)
public class AnimationSkipMixin implements TrackedAnimation {
	@Shadow
	int frame;
	@Shadow
	@Final
	SpriteContents.AnimatedTexture animationInfo;
	@Unique
	private @Nullable SpriteContents afterburner$contents;
	@Unique
	private boolean afterburner$tracked, afterburner$skip, afterburner$stale;

	@Override
	public void afterburner$track(SpriteContents contents) {
		afterburner$contents = contents;
	}

	@Inject(method = "tickAndUpload", at = @At("HEAD"))
	private void afterburner$skipUnseen(int x, int y, CallbackInfo ci) {
		SpriteContents contents = afterburner$contents;
		afterburner$tracked = contents != null && ((SeenSprite) contents).afterburner$tracked();
		afterburner$skip = afterburner$tracked && AnimatedSprites.skips(contents);
		if (!afterburner$skip && afterburner$stale) {
			// Back on screen after frames went by unseen: uploads the one it's at now.
			afterburner$stale = false;
			animationInfo.uploadFrame(x, y, animationInfo.frames.get(frame).index);
			AnimatedSprites.redrawn++;
		}
	}

	@WrapOperation(method = "tickAndUpload", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/texture/SpriteContents$AnimatedTexture;uploadFrame(III)V"))
	private void afterburner$frame(SpriteContents.AnimatedTexture texture, int x, int y, int index, Operation<Void> original) {
		if (afterburner$uploads()) original.call(texture, x, y, index);
	}

	@WrapOperation(method = "tickAndUpload", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/texture/SpriteContents$InterpolationData;uploadInterpolatedFrame(IILnet/minecraft/client/renderer/texture/SpriteContents$Ticker;)V"))
	private void afterburner$blend(SpriteContents.InterpolationData data, int x, int y, SpriteContents.Ticker ticker, Operation<Void> original) {
		if (afterburner$uploads()) original.call(data, x, y, ticker);
	}

	@Unique
	private boolean afterburner$uploads() {
		if (afterburner$skip) {
			afterburner$stale = true;
			AnimatedSprites.skipped++;
			return false;
		}
		if (afterburner$tracked) AnimatedSprites.redrawn++;
		return true;
	}
}
