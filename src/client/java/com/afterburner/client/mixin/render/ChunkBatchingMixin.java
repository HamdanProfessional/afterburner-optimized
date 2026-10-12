package com.afterburner.client.mixin.render;

import com.afterburner.client.render.ChunkBatcher;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws each chunk layer a region at a time ({@link ChunkBatcher}) in place of vanilla's loop over the sections, after
 * vanilla set up the layer's render state and scheduled its translucent re-sorts. Also hands the batcher the pieces of
 * the level renderer it needs: the chunk renderer of the world being drawn ({@link ChunkBatcher#world}), the occlusion
 * graph its wide section list is picked from and the frustum that list is made wider than
 * ({@link ChunkBatcher#cullFrustum}).
 */
@Mixin(LevelRenderer.class)
public class ChunkBatchingMixin {
	@Shadow
	@Final
	private Minecraft minecraft;
	@Shadow
	@Final
	private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
	@Shadow
	private @Nullable SectionRenderDispatcher sectionRenderDispatcher;
	@Shadow
	@Final
	private SectionOcclusionGraph sectionOcclusionGraph;

	@Inject(method = "renderSectionLayer", cancellable = true, at = @At(value = "INVOKE_STRING",
			target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V", args = "ldc=filterempty"))
	private void afterburner$drawBatched(RenderType renderType, double x, double y, double z, Matrix4f modelView, Matrix4f projection,
			CallbackInfo ci) {
		ProfilerFiller profiler = minecraft.getProfiler();
		profiler.push("afterburner_batched");
		ChunkBatcher.world(sectionRenderDispatcher);
		ChunkBatcher.occlusionGraph(sectionOcclusionGraph);
		ChunkBatcher.draw(renderType, visibleSections, x, y, z, modelView, projection);
		profiler.pop();
		renderType.clearRenderState();
		ci.cancel();
	}

	/** Every section is rebuilt (a reload, a render distance or dimension change): nothing kept of the old ones is drawn. */
	@Inject(method = "allChanged", at = @At("HEAD"))
	private void afterburner$allChanged(CallbackInfo ci) {
		ChunkBatcher.worldChanged();
	}

	/** The camera's culling frustum (the game makes it wider for the sections), which the batcher's wide one is made from. */
	@Inject(method = "prepareCullFrustum", at = @At("HEAD"))
	private void afterburner$cullFrustum(Vec3 pos, Matrix4f view, Matrix4f projection, CallbackInfo ci) {
		ChunkBatcher.cullFrustum(pos.x(), pos.y(), pos.z(), view, projection);
	}

	/** The frozen frustum of a captured screenshot shows sections a pick for the camera wouldn't hold: vanilla's list is used. */
	@Inject(method = "setupRender", at = @At("HEAD"))
	private void afterburner$frustumCaptured(Camera camera, Frustum frustum, boolean captured, boolean spectator, CallbackInfo ci) {
		ChunkBatcher.frustumCaptured(captured);
	}

	/** When the occlusion graph tells vanilla to pick its visible sections again, the batcher's wide ones are picked again too. */
	@WrapOperation(method = "setupRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;consumeFrustumUpdate()Z"))
	private boolean afterburner$wideGraphChanged(SectionOcclusionGraph graph, Operation<Boolean> original) {
		boolean changed = original.call(graph);
		if (changed) ChunkBatcher.wideGraphChanged();
		return changed;
	}
}
