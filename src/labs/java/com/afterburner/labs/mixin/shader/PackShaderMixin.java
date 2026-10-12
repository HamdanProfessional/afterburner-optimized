package com.afterburner.labs.mixin.shader;

import com.afterburner.labs.shaderpack.game.PackShader;
import com.afterburner.labs.shaderpack.game.Shaderpacks;
import net.minecraft.client.renderer.ShaderInstance;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A game shader applied in a pack's buffers draws with the pack's program for it (see Shaderpacks#applied), which is kept on
 * the shader; applied again or cleared, what that changed is put back first.
 */
@Mixin(ShaderInstance.class)
public abstract class PackShaderMixin implements PackShader {
	@Unique
	private @Nullable Object afterburner$packUses;

	@Override
	public @Nullable Object afterburner$packUses() {
		return this.afterburner$packUses;
	}

	@Override
	public void afterburner$setPackUses(@Nullable Object uses) {
		this.afterburner$packUses = uses;
	}

	@Inject(method = "apply", at = @At("HEAD"))
	private void afterburner$applying(CallbackInfo ci) {
		Shaderpacks.applying();
	}

	@Inject(method = "apply", at = @At("TAIL"))
	private void afterburner$applied(CallbackInfo ci) {
		Shaderpacks.applied((ShaderInstance) (Object) this);
	}

	@Inject(method = "clear", at = @At("HEAD"))
	private void afterburner$clearing(CallbackInfo ci) {
		Shaderpacks.applying();
	}
}
