package com.afterburner.client.mixin.render;

import com.afterburner.client.render.BuildStamp;
import com.afterburner.client.render.Stamped;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ByteBufferBuilder.Result.class)
public class ResultStampMixin implements Stamped {
	@Unique
	private @Nullable BuildStamp afterburner$stamp;

	@Override
	public @Nullable BuildStamp afterburner$stamp() {
		return afterburner$stamp;
	}

	@Override
	public void afterburner$setStamp(BuildStamp stamp) {
		afterburner$stamp = stamp;
	}
}
