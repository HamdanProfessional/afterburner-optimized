package com.afterburner.labs.mixin.lod;

import com.afterburner.labs.lod.Lod;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Far terrain: a section that gets a mesh or loses it, so the mask asks about its chunk soon ({@link Lod#sectionChanged}). */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class LodSectionMixin {
	@Shadow
	public abstract BlockPos getOrigin();

	@Inject(method = "setCompiled", at = @At("RETURN"))
	private void afterburner$farTerrainMesh(SectionRenderDispatcher.CompiledSection compiled, CallbackInfo ci) {
		BlockPos origin = getOrigin();
		Lod.sectionChanged(origin.getX() >> 4, origin.getZ() >> 4);
	}

	/** Before it's moved elsewhere ({@code setOrigin}) or let go of: its old place. */
	@Inject(method = "reset", at = @At("HEAD"))
	private void afterburner$farTerrainReset(CallbackInfo ci) {
		BlockPos origin = getOrigin();
		Lod.sectionChanged(origin.getX() >> 4, origin.getZ() >> 4);
	}
}
