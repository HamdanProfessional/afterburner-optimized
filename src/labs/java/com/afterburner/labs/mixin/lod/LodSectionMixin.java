package com.afterburner.labs.mixin.lod;

import com.afterburner.labs.lod.Lod;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Far terrain: a section that gets a mesh or loses it, so the mask asks about its chunk soon ({@link Lod#sectionChanged}). */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class LodSectionMixin {
	@Shadow
	public abstract long getSectionNode();

	@Inject(method = "setSectionMesh", at = @At("RETURN"))
	private void afterburner$farTerrainMesh(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> cir) {
		Lod.sectionChanged(getSectionNode());
	}

	@Inject(method = "reset", at = @At("HEAD"))
	private void afterburner$farTerrainReset(CallbackInfo ci) {
		Lod.sectionChanged(getSectionNode());
	}
}
