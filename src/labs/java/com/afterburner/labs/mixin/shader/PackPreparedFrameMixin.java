package com.afterburner.labs.mixin.shader;

import com.afterburner.labs.shaderpack.game.ShadowEntities;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The entities submitted for a shader pack's shadow alone (ShadowEntities) are drawn in the shadow map only. */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public class PackPreparedFrameMixin {
	@WrapOperation(method = "*", at = @At(value = "INVOKE",
			target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectAVLTreeMap;values()Lit/unimi/dsi/fastutil/objects/ObjectCollection;"))
	private ObjectCollection<SubmitNodeCollection> afterburner$orders(Int2ObjectAVLTreeMap<SubmitNodeCollection> orders,
			Operation<ObjectCollection<SubmitNodeCollection>> original) {
		ObjectCollection<SubmitNodeCollection> all = original.call(orders);
		return ShadowEntities.orders(orders, all);
	}
}
