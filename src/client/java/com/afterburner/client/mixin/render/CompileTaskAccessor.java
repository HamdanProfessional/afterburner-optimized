package com.afterburner.client.mixin.render;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public interface CompileTaskAccessor {
	@Accessor("isCancelled")
	AtomicBoolean afterburner$cancelled();
}
