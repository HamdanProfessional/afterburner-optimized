package com.afterburner.labs.shaderpack.game;

import org.jspecify.annotations.Nullable;

/** A game shader (ShaderInstance) with the pack's programs made for it kept on it (see {@link PackPipelines}). */
public interface PackShader {
	@Nullable Object afterburner$packUses();

	void afterburner$setPackUses(@Nullable Object uses);
}
