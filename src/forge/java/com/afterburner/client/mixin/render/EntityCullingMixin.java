package com.afterburner.client.mixin.render;

import com.afterburner.client.render.EntityCulling;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * An entity whose box is in view but hidden behind terrain counts as out of view. Renderers that also draw things
 * beyond the entity (guardian and end crystal beams, leash ropes) still check those afterwards, as in vanilla.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityCullingMixin {
	@WrapOperation(method = "shouldRender", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/culling/Frustum;isVisible(Lnet/minecraft/world/phys/AABB;)Z", ordinal = 0))
	private boolean afterburner$occluded(Frustum frustum, AABB box, Operation<Boolean> original, @Local(argsOnly = true) Entity entity,
			@Local(argsOnly = true, ordinal = 0) double camX, @Local(argsOnly = true, ordinal = 1) double camY,
			@Local(argsOnly = true, ordinal = 2) double camZ) {
		return original.call(frustum, box) && !EntityCulling.hide(entity, box, camX, camY, camZ);
	}
}
