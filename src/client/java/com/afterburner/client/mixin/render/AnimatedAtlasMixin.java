package com.afterburner.client.mixin.render;

import com.afterburner.client.render.AnimatedSprites;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Finds where the block atlas's animated textures are, and has their animations wait while off screen (see {@link AnimatedSprites}). */
@Mixin(TextureAtlas.class)
public class AnimatedAtlasMixin {
	@Shadow
	@Final
	private ResourceLocation location;

	@Inject(method = "upload", at = @At("RETURN"))
	private void afterburner$findAnimated(SpriteLoader.Preparations preparations, CallbackInfo ci) {
		if (location.equals(InventoryMenu.BLOCK_ATLAS)) {
			AnimatedSprites.atlasUploaded(preparations.regions().values(), preparations.width(), preparations.height());
		}
	}
}
