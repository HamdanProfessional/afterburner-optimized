package com.afterburner.labs.mixin.shader;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The sections the shadow map draws from, Fabulous graphics' buffers made again once a pack is off, and the frame's own depth copies (see WorldScale). */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
	@Accessor("viewArea")
	ViewArea afterburner$viewArea();

	@Invoker("initTransparency")
	void afterburner$initTransparency();

	@Invoker("shouldShowEntityOutlines")
	boolean afterburner$showsEntityOutlines();
}
