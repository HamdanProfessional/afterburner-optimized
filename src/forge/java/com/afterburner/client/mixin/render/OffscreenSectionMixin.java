package com.afterburner.client.mixin.render;

import com.afterburner.client.render.OffscreenBuilds;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicReference;

/** A section's first build lets the visibility graph reach further, so more off-screen sections may be built (see {@link OffscreenBuilds}). */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public class OffscreenSectionMixin {
	@Shadow
	@Final
	public AtomicReference<SectionMesh> sectionMesh;

	@Inject(method = "setSectionMesh", at = @At("HEAD"))
	private void afterburner$firstBuild(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> cir) {
		if (sectionMesh.get() == CompiledSectionMesh.UNCOMPILED) OffscreenBuilds.changed();
	}
}
