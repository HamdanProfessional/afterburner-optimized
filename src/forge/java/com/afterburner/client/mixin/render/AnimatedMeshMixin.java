package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedMesh;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompiledSectionMesh.class)
public class AnimatedMeshMixin implements AnimatedMesh {
	@Unique
	private SpriteContents @Nullable [] afterburner$animated;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void afterburner$takeAnimated(TranslucencyPointOfView pointOfView, SectionCompiler.Results results, long startTime, CallbackInfo ci) {
		afterburner$animated = ((AnimatedMesh) (Object) results).afterburner$animated();
	}

	@Override
	public SpriteContents @Nullable [] afterburner$animated() {
		return afterburner$animated;
	}

	@Override
	public void afterburner$setAnimated(SpriteContents @Nullable [] sprites) {
		afterburner$animated = sprites;
	}
}
