package com.afterburner.bench.mixin.client;

import com.afterburner.bench.client.BuildLog;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Counts section builds, for {@link BuildLog}. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public class BuildCountMixin {
	@Inject(method = "createCompileTask", at = @At("HEAD"))
	private void afterburner$count(RenderRegionCache cache, CallbackInfoReturnable<?> cir) {
		if (!BuildLog.ENABLED) return;
		if (((SectionRenderDispatcher.RenderSection) (Object) this).getCompiled() == SectionRenderDispatcher.CompiledSection.UNCOMPILED) BuildLog.firstBuilds++;
		else BuildLog.rebuilds++;
	}
}
