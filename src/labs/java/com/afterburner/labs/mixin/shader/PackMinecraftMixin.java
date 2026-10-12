package com.afterburner.labs.mixin.shader;

import com.afterburner.labs.shaderpack.game.Shaderpacks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** No Fabulous graphics while a pack draws: their render types would draw into the game's own buffers, not the pack's. */
@Mixin(Minecraft.class)
public abstract class PackMinecraftMixin {
	@ModifyReturnValue(method = "useShaderTransparency", at = @At("RETURN"))
	private static boolean afterburner$noFabulous(boolean fabulous) {
		return fabulous && !Shaderpacks.hidesFabulous();
	}
}
