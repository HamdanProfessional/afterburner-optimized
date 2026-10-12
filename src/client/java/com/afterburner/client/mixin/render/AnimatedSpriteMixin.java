package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedSprites;
import com.afterburner.client.render.SeenSprite;
import com.afterburner.client.render.TrackedAnimation;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteTicker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Remembers when each texture was last on screen, and ties its animation to it (see {@link AnimatedSprites}). */
@Mixin(SpriteContents.class)
public class AnimatedSpriteMixin implements SeenSprite {
	@Unique
	private int afterburner$seenAt, afterburner$listed;
	@Unique
	private boolean afterburner$ticks, afterburner$tracked;

	@Inject(method = "createTicker", at = @At("RETURN"))
	private void afterburner$tie(CallbackInfoReturnable<SpriteTicker> cir) {
		if (cir.getReturnValue() instanceof TrackedAnimation ticker) {
			ticker.afterburner$track((SpriteContents) (Object) this);
			afterburner$ticks = true;
		}
	}

	@Override
	public int afterburner$seenAt() {
		return afterburner$seenAt;
	}

	@Override
	public void afterburner$see(int tick) {
		afterburner$seenAt = tick;
	}

	@Override
	public int afterburner$listed() {
		return afterburner$listed;
	}

	@Override
	public void afterburner$list(int stamp) {
		afterburner$listed = stamp;
	}

	@Override
	public boolean afterburner$ticks() {
		return afterburner$ticks;
	}

	@Override
	public boolean afterburner$tracked() {
		return afterburner$tracked;
	}

	@Override
	public void afterburner$track() {
		afterburner$tracked = true;
	}
}
