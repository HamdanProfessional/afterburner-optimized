package com.afterburner.client.mixin.render;

import com.afterburner.client.render.SeenSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SpriteContents.class)
public class AnimatedSpriteMixin implements SeenSprite {
	@Unique
	private int afterburner$seenAt, afterburner$listed;

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
}
