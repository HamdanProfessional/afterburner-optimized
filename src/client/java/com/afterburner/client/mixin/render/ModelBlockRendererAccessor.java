package com.afterburner.client.mixin.render;

import net.minecraft.client.renderer.block.ModelBlockRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ModelBlockRenderer.class)
public interface ModelBlockRendererAccessor {
	/** Each thread's light cache (a package-private class, so only as an object here). */
	@Accessor("CACHE")
	static ThreadLocal<?> afterburner$cache() {
		throw new AssertionError();
	}
}
