package com.afterburner.client.mixin.render;

import com.afterburner.client.render.RegionFrustum;
import com.afterburner.client.render.RegionList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Vanilla picks the sections in view (every 2 degrees the camera turns, and after every new graph) by testing each
 * section of the occlusion graph against the frustum, walking the graph's linked set. The sections are tested a region at
 * a time instead ({@link RegionFrustum}), as later versions' section octree does: a region wholly in or out of view
 * answers for all its sections. And the pass walks the graph's flat copy ({@link RegionList}), kept as sections join it.
 * Same sections, same order.
 */
@Mixin(SectionOcclusionGraph.class)
public class FrustumPassMixin {
	@Shadow
	@Final
	private AtomicReference<?> currentGraph;

	@Inject(method = "addSectionsInFrustum", at = @At("HEAD"), cancellable = true)
	private void afterburner$beginPass(Frustum frustum, List<SectionRenderDispatcher.RenderSection> list, CallbackInfo ci) {
		RegionFrustum.begin(frustum);
		Object state = currentGraph.get();
		if (state == null) return;
		SectionOcclusionGraph.GraphStorage storage = ((GraphStateAccessor) state).afterburner$storage();
		if (RegionFrustum.pass((RegionList) storage, storage.renderSections.size(), frustum, list)) {
			RegionFrustum.end();
			ci.cancel();
		}
	}

	@Inject(method = "addSectionsInFrustum", at = @At("RETURN"))
	private void afterburner$endPass(Frustum frustum, List<SectionRenderDispatcher.RenderSection> list, CallbackInfo ci) {
		RegionFrustum.end();
	}

	@WrapOperation(method = "addSectionsInFrustum", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/culling/Frustum;isVisible(Lnet/minecraft/world/phys/AABB;)Z"))
	private boolean afterburner$byRegion(Frustum frustum, AABB box, Operation<Boolean> original) {
		int region = RegionFrustum.region(box);
		return region == RegionFrustum.PARTLY ? original.call(frustum, box) : region == RegionFrustum.INSIDE;
	}

	/** A section joined the graph: it's added to the flat copy too, in the same order. */
	@WrapOperation(method = "runUpdates", at = @At(value = "INVOKE", target = "Ljava/util/LinkedHashSet;add(Ljava/lang/Object;)Z"))
	private boolean afterburner$joined(LinkedHashSet<Object> set, Object node, Operation<Boolean> original,
			@Local(argsOnly = true) SectionOcclusionGraph.GraphStorage storage, @Local SectionRenderDispatcher.RenderSection section) {
		boolean added = original.call(set, node);
		if (added) ((RegionList) storage).afterburner$append(section);
		return added;
	}
}
