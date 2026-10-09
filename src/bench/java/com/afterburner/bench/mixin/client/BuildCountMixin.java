package com.afterburner.bench.mixin.client;

import com.afterburner.bench.client.BuildLog;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicReference;

/** Counts section builds, for {@link BuildLog}. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public class BuildCountMixin {
	@Shadow
	@Final
	public AtomicReference<SectionMesh> sectionMesh;

	@Inject(method = {"compileAsync", "compileSync"}, at = @At("HEAD"))
	private void afterburner$count(RenderSectionRegion region, CallbackInfo ci) {
		if (!BuildLog.ENABLED) return;
		if (sectionMesh.get() == CompiledSectionMesh.UNCOMPILED) BuildLog.firstBuilds++;
		else BuildLog.rebuilds++;
	}
}
